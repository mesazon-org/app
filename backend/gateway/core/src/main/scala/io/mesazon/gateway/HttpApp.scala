package io.mesazon.gateway

import cats.syntax.all.*
import fs2.io.net.Network
import io.mesazon.gateway.config.GatewayServerConfig
import io.mesazon.gateway.config.GatewayServerConfig.ServerConfig
import io.mesazon.gateway.service.FileService
import io.mesazon.gateway.tapir.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.EntityLimiter
import org.http4s.{HttpRoutes, Response, Status}
import smithy4s.http4s.*
import smithy4s.http4s.swagger.docs
import smithy4s.kinds.FunctorAlgebra
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import zio.*
import zio.interop.catz.*

object HttpApp {

  given Network[Task] = Network.forAsync[Task]

  private def buildSmihtyRoute[Alg[_[_, _, _, _, _]]](
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

  private val healthSmihtyRoutes = for {
    healthCheckService <- ZIO.service[smithy.HealthCheckService[Task]]
    healthCheckRoute   <- buildSmihtyRoute(healthCheckService)
  } yield healthCheckRoute

  private val externalSmithyRoutes = for {
    userSignUpService                   <- ZIO.service[smithy.UserSignUpService[Task]]
    userSignInService                   <- ZIO.service[smithy.UserSignInService[Task]]
    userOnboardService                  <- ZIO.service[smithy.UserOnboardService[Task]]
    userForgotPasswordService           <- ZIO.service[smithy.UserForgotPasswordService[Task]]
    userTokenService                    <- ZIO.service[smithy.UserTokenService[Task]]
    organizationManagementService       <- ZIO.service[smithy.OrganizationManagementService[Task]]
    userSignUpRoutes                    <- buildSmihtyRoute(userSignUpService)
    userSignInRoutes                    <- buildSmihtyRoute(userSignInService)
    userOnboardRoutes                   <- buildSmihtyRoute(userOnboardService)
    userForgotPasswordRoutes            <- buildSmihtyRoute(userForgotPasswordService)
    userTokenServiceRoutes              <- buildSmihtyRoute(userTokenService)
    organizationManagementServiceRoutes <- buildSmihtyRoute(organizationManagementService)
  } yield userSignUpRoutes <+> userOnboardRoutes <+> userSignInRoutes <+> userForgotPasswordRoutes <+> userTokenServiceRoutes <+> organizationManagementServiceRoutes

  private def externalTapirAndDocsRoutes(enableDocs: Boolean): ZIO[FileService[TapirTask], Nothing, HttpRoutes[Task]] =
    for {
      endpoints <- TapirEndpoints.allRoutesAndDocsEndpoints(enableDocs)
      streamRoutes: HttpRoutes[Task] =
        ZHttp4sServerInterpreter(TapirEndpoints.serverOptions)
          .from(endpoints.stream)
          .toRoutes
      docsRoutes: Option[HttpRoutes[Task]] = endpoints.docsOpt.map(
        ZHttp4sServerInterpreter()
          .from(_)
          .toRoutes
      )
    } yield streamRoutes <+> docsRoutes.getOrElse(HttpRoutes.empty)

  private val internalSmithyRoutes = for {
    wahaService <- ZIO.service[smithy.WahaService[Task]]
    routes      <- buildSmihtyRoute(wahaService)
  } yield routes

  private lazy val externalSmithySwaggerRoutes = docs[Task](
    smithy.UserSignUpService,
    smithy.UserSignInService,
    smithy.UserOnboardService,
    smithy.UserForgotPasswordService,
    smithy.UserTokenService,
    smithy.OrganizationManagementService,
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
        ZIO.fiberId
          .flatMap(fid =>
            ZIO.logErrorCause("Unexpected failure", Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)))
          )
          .as(Response[Task](Status.InternalServerError))
      )
      .build
      .toScopedZIO
  } yield emberServer

  val serverLayer = ZLayer.scoped {
    (for {
      config                     <- ZIO.service[GatewayServerConfig]
      healthSmithyRoutes         <- healthSmihtyRoutes
      externalSmithyRoutes       <- externalSmithyRoutes
      externalTapirAndDocsRoutes <- externalTapirAndDocsRoutes(config.enableDocs)
      externalRoutes = externalSmithyRoutes <+> externalTapirAndDocsRoutes
      internalSmithyRoutes <- internalSmithyRoutes
      externalSwaggerRoutes  = Option.when(config.enableDocs)(externalSmithySwaggerRoutes)
      externalWithDocsRoutes = externalSwaggerRoutes.map(_ <+> externalRoutes).getOrElse(externalRoutes)
      servers <-
        server(config.health, healthSmithyRoutes) &>
          server(config.external, externalWithDocsRoutes) &>
          server(config.internal, internalSmithyRoutes)
    } yield servers).forkScoped
  }
}
