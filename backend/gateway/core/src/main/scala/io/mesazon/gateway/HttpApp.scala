package io.mesazon.gateway

import cats.syntax.all.*
import fs2.io.net.Network
import io.mesazon.gateway.config.GatewayServerConfig
import io.mesazon.gateway.config.GatewayServerConfig.ServerConfig
import io.mesazon.gateway.service.{AuthorizationService, FileService}
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

  type TapirRoutes = (apiRoutes: HttpRoutes[Task], docsRoutesOpt: Option[HttpRoutes[Task]])

  given Network[Task] = Network.forAsync[Task]

  // Smithy (JSON) routes keep the conservative 2 MB request-body limit.
  private val SmithyMaxEntitySize: Long = 2L * 1024 * 1024
  // Tapir routes carry logo uploads, so they allow up to 20 MB.
  // Keep in sync with `file-service.max-organization-logo-bytes`.
  private val TapirMaxEntitySize: Long = 20L * 1024 * 1024

  private def buildSmithyRoute[Alg[_[_, _, _, _, _]]](
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

  private val healthSmithyRoutes = for {
    healthCheckService <- ZIO.service[smithy.HealthCheckService[Task]]
    healthCheckRoute   <- buildSmithyRoute(healthCheckService)
  } yield healthCheckRoute

  private val externalSmithyRoutes = for {
    userSignUpService                   <- ZIO.service[smithy.UserSignUpService[Task]]
    userSignInService                   <- ZIO.service[smithy.UserSignInService[Task]]
    userOnboardService                  <- ZIO.service[smithy.UserOnboardService[Task]]
    userForgotPasswordService           <- ZIO.service[smithy.UserForgotPasswordService[Task]]
    userTokenService                    <- ZIO.service[smithy.UserTokenService[Task]]
    organizationManagementService       <- ZIO.service[smithy.OrganizationManagementService[Task]]
    userSignUpRoutes                    <- buildSmithyRoute(userSignUpService)
    userSignInRoutes                    <- buildSmithyRoute(userSignInService)
    userOnboardRoutes                   <- buildSmithyRoute(userOnboardService)
    userForgotPasswordRoutes            <- buildSmithyRoute(userForgotPasswordService)
    userTokenServiceRoutes              <- buildSmithyRoute(userTokenService)
    organizationManagementServiceRoutes <- buildSmithyRoute(organizationManagementService)
  } yield userSignUpRoutes <+> userOnboardRoutes <+> userSignInRoutes <+> userForgotPasswordRoutes <+> userTokenServiceRoutes <+> organizationManagementServiceRoutes

  private def externalTapirAndDocsRoutes(
      enableDocs: Boolean
  ): ZIO[FileService[TapirTask] & AuthorizationService[TapirTask], Nothing, TapirRoutes] =
    for {
      endpoints <- FileServiceEndpoints.allRoutesAndDocsEndpoints(enableDocs)
      streamRoutes: HttpRoutes[Task] =
        ZHttp4sServerInterpreter(tapirServerOptions)
          .from(endpoints.stream)
          .toRoutes
      docsRoutes: Option[HttpRoutes[Task]] = endpoints.docsOpt.map(
        ZHttp4sServerInterpreter()
          .from(_)
          .toRoutes
      )
    } yield (streamRoutes, docsRoutes)

  private val internalSmithyRoutes = for {
    wahaService <- ZIO.service[smithy.WahaService[Task]]
    routes      <- buildSmithyRoute(wahaService)
  } yield routes

  private lazy val externalSmithySwaggerRoutes = docs[Task](
    smithy.UserSignUpService,
    smithy.UserSignInService,
    smithy.UserOnboardService,
    smithy.UserForgotPasswordService,
    smithy.UserTokenService,
    smithy.OrganizationManagementService,
    smithy.CustomerBookService,
    FileServiceEndpoints.smithy4sDocsID,
  )

  private def server(
      config: ServerConfig,
      routes: HttpRoutes[Task],
  ) = for {
    emberServer <- EmberServerBuilder
      .default[Task]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(routes.orNotFound)
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
      externalTapirAndDocsRoutes <- externalTapirAndDocsRoutes(config.enableDocs)
      healthSmithyRoutes         <- healthSmithyRoutes
      externalSmithyRoutes       <- externalSmithyRoutes
      internalSmithyRoutes       <- internalSmithyRoutes

      externalSwaggerRoutesOpt = Option.when(config.enableDocs)(externalSmithySwaggerRoutes)

      externalTapirApiRoutes  = EntityLimiter.httpRoutes(externalTapirAndDocsRoutes.apiRoutes, TapirMaxEntitySize)
      externalSmithyApiRoutes = EntityLimiter.httpRoutes(externalSmithyRoutes, SmithyMaxEntitySize)

      // IMPORTANT: Ordering matters all docs routes must come before the Smithy docs route
      externalDocsRoutes = externalTapirAndDocsRoutes.docsRoutesOpt.getOrElse(
        HttpRoutes.empty[Task]
      ) <+> externalSwaggerRoutesOpt.getOrElse(HttpRoutes.empty[Task])

      externalRoutes = externalTapirApiRoutes <+> externalSmithyApiRoutes <+> externalDocsRoutes
      servers <-
        server(config.health, healthSmithyRoutes) &>
          server(config.external, externalRoutes) &>
          server(config.internal, internalSmithyRoutes)
    } yield servers).forkScoped
  }
}
