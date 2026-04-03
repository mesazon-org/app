package io.mesazon.gateway

import cats.syntax.all.*
import fs2.io.net.Network
import io.mesazon.gateway.config.GatewayServerConfig
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.EntityLimiter
import smithy4s.http4s.*
import smithy4s.http4s.swagger.docs
import smithy4s.kinds.FunctorAlgebra
import zio.*
import zio.interop.catz.*

import scala.util.chaining.scalaUtilChainingOps

import GatewayServerConfig.ServerConfig

object HttpApp {

  given Network[Task] = Network.forAsync[Task]
  private val dsl     = Http4sDsl[Task]
  import dsl.*

  private def buildRoute[Alg[_[_, _, _, _, _]]](
      impl: FunctorAlgebra[Alg, Task],
      middleware: Option[ServerEndpointMiddleware[Task]] = None,
  )(using smithy4s.Service[Alg]): ZIO[Scope, Throwable, HttpRoutes[Task]] =
    SimpleRestJsonBuilder
      .routes(impl)
      .pipe(builder => middleware.fold(builder)(builder.middleware))
      .resource
      .toScopedZIO

  private val healthRoutesResource = for {
    healthCheckService <- ZIO.service[smithy.HealthCheckService[Task]]
    healthCheckRoute   <- buildRoute(healthCheckService)
  } yield healthCheckRoute

  private val externalRoutesResource = for {
    serverMiddleware      <- ZIO.service[ServerEndpointMiddleware.Simple[Task]]
    userManagementService <- ZIO.service[smithy.UserManagementService[Task]]
    userContactsService   <- ZIO.service[smithy.UserContactsService[Task]]
    authenticationService <- ZIO.service[smithy.AuthenticationService[Task]]
    userManagementRoute   <- buildRoute(userManagementService, Some(serverMiddleware))
    authenticationRoute   <- buildRoute(authenticationService, None)
    userContactsRoute     <- buildRoute(userContactsService, Some(serverMiddleware))
  } yield userManagementRoute <+> userContactsRoute <+> authenticationRoute

  private val internalRoutesResource = for {
    wahaService <- ZIO.service[smithy.WahaService[Task]]
    routes      <- buildRoute(wahaService)
  } yield routes

  private val docsRoutes = docs[Task](
    smithy.UserManagementService,
    smithy.AuthenticationService,
    smithy.UserContactsService,
    smithy.WahaService,
  )

  private def server(
      config: ServerConfig,
      routes: HttpRoutes[Task],
  ) = for {
    emberServer <- EmberServerBuilder
      .default[Task]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(EntityLimiter.httpRoutes(routes).orNotFound) // Limits the size of the request body to 2MB
      .withErrorHandler(error =>
        ZIO.fiberId.flatMap(fid =>
          ZIO.logErrorCause("Unexpected failure", Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)))
        ) *> InternalServerError()
      )
      .build
      .toScopedZIO
  } yield emberServer

  val serverLayer = ZLayer.scoped {
    (for {
      config         <- ZIO.service[GatewayServerConfig]
      healthRoutes   <- healthRoutesResource
      externalRoutes <- externalRoutesResource
      internalRoutes <- internalRoutesResource
      servers        <-
        server(config.health, healthRoutes <+> docsRoutes) &>
          server(config.external, externalRoutes) &>
          server(config.internal, internalRoutes)
    } yield servers).forkScoped
  }
}
