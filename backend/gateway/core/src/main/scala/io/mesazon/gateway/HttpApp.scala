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

import GatewayServerConfig.ServerConfig

object HttpApp {

  given Network[Task] = Network.forAsync[Task]
  private val dsl     = Http4sDsl[Task]
  import dsl.*

  private def buildRoute[Alg[_[_, _, _, _, _]]](
      impl: FunctorAlgebra[Alg, Task]
  )(using smithy4s.Service[Alg]): ZIO[Scope & ServerEndpointMiddleware.Simple[Task], Throwable, HttpRoutes[Task]] =
    for {
      middleware <- ZIO.service[ServerEndpointMiddleware.Simple[Task]]
      httpRoutes <- SimpleRestJsonBuilder
        .routes(impl)
        .middleware(middleware)
        .resource
        .toScopedZIO
    } yield httpRoutes

  private val healthRoutesResource = for {
    healthCheckService <- ZIO.service[smithy.HealthCheckService[Task]]
    healthCheckRoute   <- buildRoute(healthCheckService)
  } yield healthCheckRoute

  private val externalRoutesResource = for {
    userSignUpService  <- ZIO.service[smithy.UserSignUpService[Task]]
    userSignInService  <- ZIO.service[smithy.UserSignInService[Task]]
    userOnboardService <- ZIO.service[smithy.UserOnboardService[Task]]
    userSignUpRoutes   <- buildRoute(userSignUpService)
    userSignInRoutes   <- buildRoute(userSignInService)
    userOnboardRoutes  <- buildRoute(userOnboardService)
  } yield userSignUpRoutes <+> userOnboardRoutes <+> userSignInRoutes

  private val internalRoutesResource = for {
    wahaService <- ZIO.service[smithy.WahaService[Task]]
    routes      <- buildRoute(wahaService)
  } yield routes

  private val docsRoutes = docs[Task](
    smithy.UserSignUpService,
    smithy.UserOnboardService,
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
      externalWithDocsRoutes = Option.when(config.enableDocs)(externalRoutes <+> docsRoutes).getOrElse(externalRoutes)
      servers <-
        server(config.health, healthRoutes) &>
          server(config.external, externalWithDocsRoutes) &>
          server(config.internal, internalRoutes)
    } yield servers).forkScoped
  }
}
