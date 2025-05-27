package io.rikkos.gateway

import io.rikkos.domain.AppName
import io.rikkos.gateway.auth.*
import io.rikkos.gateway.config.*
import io.rikkos.gateway.middleware.*
import io.rikkos.gateway.repository.*
import io.rikkos.gateway.service.*
import org.slf4j.bridge.SLF4JBridgeHandler
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault {

  private val AppNameLive = ZLayer.succeed(AppName("gateway-api"))

  // Preconfigure the logger to use SLF4J
  // Preconfigure to use typesafe config reader(application.conf)
  // Preconfigure to use SLF4JBridgeHandler this is to connect jul with SLF4J
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers >>>
    SLF4J.slf4j ++ ZLayer(ZIO.succeedBlocking {
      SLF4JBridgeHandler.removeHandlersForRootLogger()
      SLF4JBridgeHandler.install()
    }) ++ Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val app = HttpApp.serverLayer.launch
    .provide(
      AppNameLive, // Used across components for metadata

      // Http
      HealthCheckService.live,
      UserManagementService.live,

      // Repository
      UserRepository.layer,

      // Auth
      AuthorizationService.live,
      AuthorizationState.live,

      // Middleware
      ServerMiddleware.live,

      // Config
      GatewayServerConfig.live,
    )

  override def run: URIO[Any, ExitCode] =
    app
      .flatMapError(error =>
        ZIO.fiberId.flatMap(fid =>
          ZIO
            .logErrorCause(
              "App failed to start with error",
              Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)),
            )
        )
      )
      .catchAllCause(cause => ZIO.logErrorCause("App failed to start with cause", cause))
      .catchAllDefect(error =>
        ZIO.fiberId.flatMap(fid =>
          ZIO.logErrorCause(
            "App failed to start with defect",
            Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)),
          )
        )
      )
      .exitCode
}
