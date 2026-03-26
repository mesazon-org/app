package io.mesazon.gateway

import com.google.i18n.phonenumbers.*
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.middleware.ServerMiddleware
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.stream.*
import io.mesazon.generator.IDGenerator
import io.mesazon.waha.*
import org.slf4j.bridge.SLF4JBridgeHandler
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

import validation.*

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
      AuthenticationService.live,

      // Repository
      PostgresTransactor.live,
      PingRepository.live,
      UserManagementRepository.live,
      UserContactRepository.live,
      WahaRepository.live,

      // Queries
      WahaQueries.live,
      UserManagementQueries.live,
      UserContactQueries.live,

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
      OpenAIClientConfig.live,

      // Validators
      EmailValidator.emailValidatorLive,
      PhoneNumberValidator.phoneNumberRegionValidatorLive,
      PhoneNumberValidator.wahaPhoneNumberValidatorLive,
      UserManagementValidators.onboardUserDetailsRequestValidatorLive,
      UserManagementValidators.updateUserDetailsRequestValidatorLive,
      UserContactsValidators.upsertUserContactsValidatorLive,
      WahaValidator.wahaMessageRequestValidatorLive,

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
