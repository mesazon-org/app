package io.mesazon.gateway

import cats.syntax.all.*
import fs2.io.net.Network
import io.mesazon.gateway.config.GatewayServerConfig
import io.mesazon.gateway.config.GatewayServerConfig.ServerConfig
import io.mesazon.gateway.tapir.*
import io.mesazon.gateway.service.*
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.EntityLimiter
import smithy4s.http4s.*
import smithy4s.http4s.swagger.docs
import smithy4s.kinds.FunctorAlgebra
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.*
import sttp.tapir.ztapir.*
import zio.*
import zio.interop.catz.*

object HttpApp {

  given Network[Task] = Network.forAsync[Task]
  private val dsl     = Http4sDsl[Task]
  import dsl.*

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

  private val externalTapirRoutes = for {
    fileService <- ZIO.service[FileService[TapirTask]]
    uploadOrganizationLogoEndpoint = TapirEndpoints.uploadOrganizationLogoPostEndpoint.zServerLogic[Any](
      fileService.uploadOrganizationLogo
    )
    routes = ZHttp4sServerInterpreter()
      .from(
        List(
          uploadOrganizationLogoEndpoint
        )
      )
      .toRoutes
    swaggerEndpoints = SwaggerInterpreter(
      swaggerUIOptions = SwaggerUIOptions.default.copy(pathPrefix = List("file-docs"))
    ).fromServerEndpoints(
      List(
        uploadOrganizationLogoEndpoint.widen[Clock]
      ),
      "File API",
      "1.0",
    )
    swaggerRoutes = ZHttp4sServerInterpreter().from(swaggerEndpoints).toRoutes
  } yield routes -> swaggerRoutes

  private val internalSmithyRoutes = for {
    wahaService <- ZIO.service[smithy.WahaService[Task]]
    routes      <- buildSmihtyRoute(wahaService)
  } yield routes

  private val docsSmithyRoutes = docs[Task](
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
        ZIO.fiberId.flatMap(fid =>
          ZIO.logErrorCause("Unexpected failure", Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)))
        ) *> InternalServerError()
      )
      .build
      .toScopedZIO
  } yield emberServer

  val serverLayer = ZLayer.scoped {
    (for {
      config                                 <- ZIO.service[GatewayServerConfig]
      healthSmithyRoutes                     <- healthSmihtyRoutes
      externalSmithyRoutes                   <- externalSmithyRoutes
      (externalTapirRoutes, docsTapirRoutes) <- externalTapirRoutes
      externalRoutes = externalSmithyRoutes <+> externalTapirRoutes
      internalSmithyRoutes <- internalSmithyRoutes
      externalWithDocsRoutes = Option
        .when(config.enableDocs)(externalRoutes <+> docsSmithyRoutes <+> docsTapirRoutes)
        .getOrElse(externalRoutes)
      servers <-
        server(config.health, healthSmithyRoutes) &>
          server(config.external, externalWithDocsRoutes) &>
          server(config.internal, internalSmithyRoutes)
    } yield servers).forkScoped
  }
}
