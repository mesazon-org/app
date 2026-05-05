package io.mesazon.gateway

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.middleware.ServerMiddleware
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.state.*
import io.mesazon.gateway.stream.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.generator.IDGenerator
import io.mesazon.waha.*
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
      PhoneNumberUtil.live,
      OtpGenerator.live,

      // Services
      AuthenticationService.live,
      AuthorizationService.live,
      HealthCheckService.live,
      WahaService.live,
      UserSignUpService.live,
      UserSignInService.live,
      UserOnboardService.live,
      JwtService.live,
      PasswordService.live,

      // Repository
      PostgresTransactor.live,
      PingRepository.live,
      WahaRepository.live,
      UserOtpRepository.live,
      UserTokenRepository.live,
      UserDetailsRepository.live,
      UserCredentialsRepository.live,
      UserActionAttemptRepository.live,

      // Queries
      WahaQueries.live,
      UserOtpQueries.live,
      UserTokenQueries.live,
      UserDetailsQueries.live,
      UserCredentialsQueries.live,
      UserActionAttemptQueries.live,

      // State
      AuthState.live,

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
      EmailConfig.live,
      UserSignUpConfig.live,
      JwtConfig.live,
      PasswordConfig.live,
      UserOnboardConfig.live,
      TwilioClientConfig.live,
      AuthenticationConfig.live,

      // Domain validators
      EmailDomainValidator.live,
      PhoneNumberDomainValidator.live,
      WahaPhoneNumberDomainValidator.live,

      // Service validators
      SignUpEmailPostRequestServiceValidator.live,
      BasicCredentialsRequestServiceValidator.live,
      SignUpVerifyEmailPostRequestServiceValidator.live,
      OnboardPasswordPostRequestServiceValidator.live,
      OnboardDetailsPostRequestServiceValidator.live,
      OnboardVerifyPhoneNumberPostRequestServiceValidator.live,
      WahaServiceValidator.live,

      // Clients
      SttpBackend.live,
      WahaClient.live,
      OpenAIClient.live,
      EmailClient.live,
      TwilioClient.live,

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
