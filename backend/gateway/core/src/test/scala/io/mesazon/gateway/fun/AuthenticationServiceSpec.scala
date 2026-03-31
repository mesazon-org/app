package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.auth.OtpGenerator
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.{smithy, Mocks}
import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class AuthenticationServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries {

  "AuthenticationService" when {
    "signUpEmail" should {
      "successfully sign up a new user with valid email" in new TestContext {
        val signUpEmailRequest    = arbitrarySample[smithy.SignUpEmailRequest]
        val authenticationService = buildAuthenticationService()

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioEither

        assert(response.isRight)

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 0
        insertUserOnboardEmailRef.get.zioValue shouldBe 1
        updateUserOnboardRef.get.zioValue shouldBe 0
        upsertUserOtpRef.get.zioValue shouldBe 1
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 1
      }

      "successfully re-sign up a user with EmailConfirmation stage but no user otp found" in new TestContext {
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = OnboardStage.EmailConfirmation)
        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow)
        )

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioEither

        assert(response.isRight)

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 1
        insertUserOnboardEmailRef.get.zioValue shouldBe 0
        updateUserOnboardRef.get.zioValue shouldBe 1
        upsertUserOtpRef.get.zioValue shouldBe 1
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 1
      }

      "successfully re-sign up a user with EmailConfirmation | EmailConfirmed stage and user otp being expired in less than cooldown" in new TestContext {
        val expiredAt =
          Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(authenticationConfig.otpResendCooldown.toSeconds - 2)
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = OnboardStage.EmailConfirmation)
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userOnboardRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(expiredAt),
          )
        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioValue

        userOtpRow.otpID should not be response.otpID

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 1
        insertUserOnboardEmailRef.get.zioValue shouldBe 0
        updateUserOnboardRef.get.zioValue shouldBe 1
        upsertUserOtpRef.get.zioValue shouldBe 1
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 1
      }

      "successfully re-sign up a user with EmailConfirmation | EmailConfirmed stage and user otp being expired in more than cooldown" in new TestContext {
        val expiredAt =
          Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(authenticationConfig.otpResendCooldown.toSeconds + 2)
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = OnboardStage.EmailConfirmation)
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userOnboardRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(expiredAt),
          )
        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioValue

        userOtpRow.otpID.value should not be response.otpID

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 1
        insertUserOnboardEmailRef.get.zioValue shouldBe 0
        updateUserOnboardRef.get.zioValue shouldBe 0
        upsertUserOtpRef.get.zioValue shouldBe 0
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 0
      }

      "successfully re-sign up a user with other than EmailConfirmation | EmailConfirmed stage and user otp being expired in less than cooldown" in new TestContext {
        val expiredAt =
          Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(authenticationConfig.otpResendCooldown.toSeconds - 2)
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = OnboardStage.DetailsProvided)
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userOnboardRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(expiredAt),
          )
        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioEither

        assert(response.isRight)

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 1
        insertUserOnboardEmailRef.get.zioValue shouldBe 0
        updateUserOnboardRef.get.zioValue shouldBe 0
        upsertUserOtpRef.get.zioValue shouldBe 0
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 0
      }

      "retry and fail with InternalServerError when sending email failing" in new TestContext {
        val signUpEmailRequest    = arbitrarySample[smithy.SignUpEmailRequest]
        val authenticationService = buildAuthenticationService(
          maybeEmailClientServiceError = Some(
            ServiceError.InternalServerError.UnexpectedError("Failed to send email due to SMTP server error")
          )
        )

        authenticationService
          .signUpEmail(signUpEmailRequest)
          .zioError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 0
        insertUserOnboardEmailRef.get.zioValue shouldBe 1
        updateUserOnboardRef.get.zioValue shouldBe 0
        upsertUserOtpRef.get.zioValue shouldBe 1
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe authenticationConfig.sendEmailVerificationEmailMaxRetries + 1
      }

      "fail with BadRequest when request is invalid" in new TestContext {
        val signUpEmailRequest    = arbitrarySample[smithy.SignUpEmailRequest]
        val authenticationService = buildAuthenticationService(
          emailValidatorMaybeError = Some(
            InvalidFieldError(
              "email",
              "Invalid email",
              "invalidemail",
            )
          )
        )

        authenticationService
          .signUpEmail(signUpEmailRequest)
          .zioEither
          .left
          .value
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()

        getUserOnboardByEmailRef.get.zioValue shouldBe 0
        getUserOtpByUserIDRef.get.zioValue shouldBe 0
        insertUserOnboardEmailRef.get.zioValue shouldBe 0
        updateUserOnboardRef.get.zioValue shouldBe 0
        upsertUserOtpRef.get.zioValue shouldBe 0
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 0
      }

      "fail with InternalServer when fail unexpectedly" in new TestContext {
        val signUpEmailRequest    = arbitrarySample[smithy.SignUpEmailRequest]
        val authenticationService = buildAuthenticationService(
          maybeUserManagementRepositoryUnexpectedError = Some(new RuntimeException("Database connection failed"))
        )

        authenticationService
          .signUpEmail(signUpEmailRequest)
          .zioEither
          .left
          .value
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        getUserOnboardByEmailRef.get.zioValue shouldBe 1
        getUserOtpByUserIDRef.get.zioValue shouldBe 0
        insertUserOnboardEmailRef.get.zioValue shouldBe 0
        updateUserOnboardRef.get.zioValue shouldBe 0
        upsertUserOtpRef.get.zioValue shouldBe 0
        updateUserOtpRef.get.zioValue shouldBe 0
        sendEmailVerificationEmailCounterRef.get.zioValue shouldBe 0
      }
    }
  }

  trait TestContext {
    val insertUserOnboardEmailRef            = Ref.make(0).zioValue
    val upsertUserOtpRef                     = Ref.make(0).zioValue
    val updateUserOtpRef                     = Ref.make(0).zioValue
    val updateUserOnboardRef                 = Ref.make(0).zioValue
    val sendEmailVerificationEmailCounterRef = Ref.make(0).zioValue
    val getUserOnboardByEmailRef             = Ref.make(0).zioValue
    val getUserOtpByUserIDRef                = Ref.make(0).zioValue

    val authenticationConfig = AuthenticationConfig(
      otpExpiration = 10.seconds,
      otpResendCooldown = 5.seconds,
      sendEmailVerificationEmailMaxRetries = 3,
      sendEmailVerificationEmailRetryDelay = 100.milliseconds,
    )

    def buildAuthenticationService(
        userOnboardRows: Map[UserID, UserOnboardRow] = Map.empty,
        userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
        maybeUserManagementRepositoryUnexpectedError: Option[Throwable] = None,
        maybeUserManagementRepositoryServiceError: Option[ServiceError] = None,
        maybeEmailClientServiceError: Option[ServiceError] = None,
        emailValidatorMaybeError: Option[InvalidFieldError] = None,
    ): smithy.AuthenticationService[Task] =
      ZIO
        .service[smithy.AuthenticationService[Task]]
        .provide(
          AuthenticationService.live,
          Mocks.emailValidatorLive(emailValidatorMaybeError),
          Mocks.userManagementRepositoryLive(
            userOtpRows = userOtpRows,
            userOnboardRows = userOnboardRows,
            updateUserOnboardRef = updateUserOnboardRef,
            upsertUserOtpRef = upsertUserOtpRef,
            updateUserOtpRef = updateUserOtpRef,
            getUserOtpByUserIDRef = getUserOtpByUserIDRef,
            getUserOnboardByEmailRef = getUserOnboardByEmailRef,
            insertUserOnboardEmailRef = insertUserOnboardEmailRef,
            maybeUnexpectedError = maybeUserManagementRepositoryUnexpectedError,
            maybeServiceError = maybeUserManagementRepositoryServiceError,
          ),
          Mocks.emailClientLive(
            maybeServiceError = maybeEmailClientServiceError,
            sendEmailVerificationEmailCounterRef = sendEmailVerificationEmailCounterRef,
          ),
          OtpGenerator.live,
          TimeProvider.liveSystemUTC,
          IDGenerator.uuidGeneratorLive,
          ZLayer.succeed(authenticationConfig),
        )
        .zioValue
  }
}
