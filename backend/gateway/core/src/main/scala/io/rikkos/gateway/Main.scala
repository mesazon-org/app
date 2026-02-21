package io.rikkos.gateway

import com.google.i18n.phonenumbers.*
import io.mesazon.waha.WahaClient
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.gateway.*
import io.rikkos.gateway.auth.*
import io.rikkos.gateway.clients.*
import io.rikkos.gateway.config.*
import io.rikkos.gateway.middleware.*
import io.rikkos.gateway.repository.*
import io.rikkos.gateway.repository.queries.WahaQueries
import io.rikkos.gateway.service.*
import io.rikkos.gateway.stream.*
import io.rikkos.gateway.validation.*
import io.rikkos.generator.IDGenerator
import org.slf4j.bridge.SLF4JBridgeHandler
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault {

  private val AppNameLive = ZLayer.succeed(AppName("gateway-api"))

  // Preconfigure the logger to use SLF4J
  // Preconfigure to use typesafe config reader(application.conf)
  // Preconfigure to use SLF4JBridgeHandler this is to route jul to SLF4J
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers >>>
    SLF4J.slf4j ++ ZLayer(ZIO.succeedBlocking {
      SLF4JBridgeHandler.removeHandlersForRootLogger()
      SLF4JBridgeHandler.install()
    }) ++ Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val app: Task[Any] = (HttpApp.serverLayer.launch &> StreamApp.streamsLayer.launch)
    .provide(
      AppNameLive, // Used across components for metadata

      // Utils
      TimeProvider.liveSystemUTC,
      IDGenerator.uuidGeneratorLive,
      ZLayer.succeed(PhoneNumberUtil.getInstance()),

      // Services
      HealthCheckService.live,
      UserManagementService.live,
      UserContactsService.live,
      WahaService.live,

      // Repository
      PostgresTransactor.live,
      PingRepository.live,
      UserRepository.live,
      UserContactsRepository.live,
      WahaRepository.live,

      // Queries
      WahaQueries.live,

      // Auth
      AuthorizationService.live,
      AuthorizationState.live,

      // Middleware
      ServerMiddleware.live,

      // Config
      DatabaseConfig.live,
      GatewayServerConfig.live,
      PhoneNumberValidatorConfig.live,
      ReplyingToMessagesCronJobConfig.live,
      wahaConfigLive,
      RepositoryConfig.live,
      HttpClientConfig.live,
      OpenAIConfig.live,

      // Validators
      PhoneNumberValidator.phoneNumberValidatorLive,
      UserManagementValidators.onboardUserDetailsRequestValidatorLive,
      UserManagementValidators.updateUserDetailsRequestValidatorLive,
      UserContactsValidators.upsertUserContactsValidatorLive,

      // Clients
      SttpBackend.live,
      WahaClient.live,
      OpenAIClient.openAILive,
      OpenAIClient.live,

      // Streams
      ReplyingToMessagesCronJobStream.live,

      // FiberRefs
      ZLayer
        .scoped(FiberRef.make(Option.empty[AuthedUser]))
        .fresh, // `.fresh` is to create a new FiberRef for each component
    )

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
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
}
