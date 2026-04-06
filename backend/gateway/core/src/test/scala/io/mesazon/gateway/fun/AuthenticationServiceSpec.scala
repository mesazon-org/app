package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.auth.OtpGenerator
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.VerifyEmailValidator
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

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedInsertUserOnboardEmailCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
      }

      "successfully re-sign up a user with sign up email stages but no user otp found" in new TestContext {
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val signUpEmailStage   = Random.shuffle(OnboardStage.signupEmailStages).zioValue.head
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = signUpEmailStage)
        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow)
        )

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioEither

        assert(response.isRight)

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpdateUserOnboardCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )

        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
      }

      "successfully re-sign up a user with sign up email stages and user otp is expired or expiring soon" in new TestContext {
        val expiredTimeSeconds = Random.nextIntBetween(2, 100).zioValue
        val expiredAt          =
          Instant
            .now()
            .truncatedTo(ChronoUnit.MILLIS)
            .plusSeconds(authenticationConfig.otpResendCooldown.toSeconds - expiredTimeSeconds)
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val signUpEmailStage   = Random.shuffle(OnboardStage.signupEmailStages).zioValue.head
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = signUpEmailStage)
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
        response.otpExpiresInSeconds.toInt should be <= 10
        response.otpExpiresInSeconds.toInt should be >= 9

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpdateUserOnboardCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )

        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
      }

      "successfully re-sign up a user with sign up email stages and user otp is not expired or expiring soon" in new TestContext {
        val expiredAt =
          Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(authenticationConfig.otpResendCooldown.toSeconds + 2)
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val signUpEmailStage   = Random.shuffle(OnboardStage.signupEmailStages).zioValue.head
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = signUpEmailStage)
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
        response.otpExpiresInSeconds.toInt should be <= 10
        response.otpExpiresInSeconds.toInt should be >= 9

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpdateUserOnboardCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )

        checkEmailClient()
      }

      "successfully re-sign up a user with not a sing up email stage and user otp being expired in less than cooldown" in new TestContext {
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

        val response = authenticationService.signUpEmail(signUpEmailRequest).zioValue

        response.otpExpiresInSeconds.toInt should be <= 10
        response.otpExpiresInSeconds.toInt should be >= 9

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
        )

        checkEmailClient()
      }

      "retry and fail with InternalServerError when sending email failing for new user" in new TestContext {
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

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedInsertUserOnboardEmailCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )

        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = authenticationConfig.sendEmailVerificationEmailMaxRetries + 1
        )
      }

      "retry and fail with InternalServerError when sending email failing for re-sign up email user" in new TestContext {
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val signUpEmailStage   = Random.shuffle(OnboardStage.signupEmailStages).zioValue.head
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email), stage = signUpEmailStage)
        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow),
          maybeEmailClientServiceError = Some(
            ServiceError.InternalServerError.UnexpectedError("Failed to send email due to SMTP server error")
          ),
        )

        authenticationService
          .signUpEmail(signUpEmailRequest)
          .zioError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpdateUserOnboardCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )

        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = authenticationConfig.sendEmailVerificationEmailMaxRetries + 1
        )
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
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest(
          code = "INVALID_FIELDS_ERROR",
          fields = Some(List("email")),
        )

        checkUserManagementRepository()

        checkEmailClient()
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

        checkUserManagementRepository(
          expectedGetUserOnboardByEmailCalls = 1
        )

        checkEmailClient()
      }
    }
    "verifyEmail" should {
      "successfully verify email with valid otp" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(stage = OnboardStage.EmailConfirmation)
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            otpID = OtpID.assume(verifyEmailRequest.otpID),
            userID = userOnboardRow.userID,
            otpType = OtpType.EmailVerification,
            otp = Otp.assume(verifyEmailRequest.otp),
            expiresAt = ExpiresAt.assume(Instant.now().plusSeconds(300)),
          )

        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val response = authenticationService.verifyEmail(verifyEmailRequest).zioEither

        assert(response.isRight)

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1,
          expectedGetUserOnboardCalls = 1,
          expectedUpdateUserOnboardCalls = 1,
          expectedDeleteUserOtpCalls = 1,
        )

        checkEmailClient()
      }

      "fail to verify email with wrong otp" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]
        val userOtpRow         = arbitrarySample[UserOtpRow]
          .copy(
            otpID = OtpID.assume(verifyEmailRequest.otpID),
            otpType = OtpType.EmailVerification,
            otp = Otp.assume("AAA111"),
            expiresAt = ExpiresAt.assume(Instant.now().plusSeconds(300)),
          )

        val authenticationService = buildAuthenticationService(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow)
        )

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1
        )

        checkEmailClient()
      }

      "fail to verify email with expired otp" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]
        val userOtpRow         = arbitrarySample[UserOtpRow]
          .copy(
            otpID = OtpID.assume(verifyEmailRequest.otpID),
            otpType = OtpType.EmailVerification,
            otp = Otp.assume(verifyEmailRequest.otp),
            expiresAt = ExpiresAt.assume(Instant.now().minusSeconds(300)),
          )

        val authenticationService = buildAuthenticationService(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow)
        )

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1
        )

        checkEmailClient()
      }

      "fail to verify email when otp type is not email verification" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]
        val userOtpRow         = arbitrarySample[UserOtpRow]
          .copy(
            otpID = OtpID.assume(verifyEmailRequest.otpID),
            otpType = OtpType.PhoneVerification,
            otp = Otp.assume(verifyEmailRequest.otp),
            expiresAt = ExpiresAt.assume(Instant.now().plusSeconds(300)),
          )

        val authenticationService = buildAuthenticationService(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow)
        )

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1,
          expectedDeleteUserOtpCalls = 1,
        )

        checkEmailClient()
      }

      "fail to verify email when user onboard stage is not email confirmation" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(stage = OnboardStage.DetailsProvided)
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            otpID = OtpID.assume(verifyEmailRequest.otpID),
            userID = userOnboardRow.userID,
            otpType = OtpType.EmailVerification,
            otp = Otp.assume(verifyEmailRequest.otp),
            expiresAt = ExpiresAt.assume(Instant.now().plusSeconds(300)),
          )

        val authenticationService = buildAuthenticationService(
          userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1,
          expectedGetUserOnboardCalls = 1,
          expectedDeleteUserOtpCalls = 1,
        )

        checkEmailClient()
      }

      "fail with bad request when request is invalid" in new TestContext {
        val verifyEmailRequest    = arbitrarySample[smithy.VerifyEmailRequest].copy(otp = "invalidotp")
        val authenticationService = buildAuthenticationService()

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioEither
          .left
          .value
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest(
          code = "INVALID_FIELDS_ERROR",
          fields = Some(List("otp")),
        )

        checkUserManagementRepository()

        checkEmailClient()
      }

      "fail to verify email when no user onboard found for userID in otp" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]
        val userOtpRow         = arbitrarySample[UserOtpRow]
          .copy(
            otpID = OtpID.assume(verifyEmailRequest.otpID),
            otpType = OtpType.EmailVerification,
            otp = Otp.assume(verifyEmailRequest.otp),
            expiresAt = ExpiresAt.assume(Instant.now().plusSeconds(300)),
          )

        val authenticationService = buildAuthenticationService(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow)
        )

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1,
          expectedGetUserOnboardCalls = 1,
        )

        checkEmailClient()
      }

      "fail to verify email when no user otp found for otpID" in new TestContext {
        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest]

        val authenticationService = buildAuthenticationService()

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1
        )

        checkEmailClient()
      }

      "fail with InternalServer when fail unexpectedly" in new TestContext {
        val verifyEmailRequest    = arbitrarySample[smithy.VerifyEmailRequest]
        val authenticationService = buildAuthenticationService(
          maybeUserManagementRepositoryUnexpectedError = Some(new RuntimeException("Database connection failed"))
        )

        authenticationService
          .verifyEmail(verifyEmailRequest)
          .zioEither
          .left
          .value
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        checkUserManagementRepository(
          expectedGetUserOtpCalls = 1
        )

        checkEmailClient()
      }
    }
  }

  trait TestContext extends UserManagementRepositoryMock, EmailClientMock {

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
          userManagementRepositoryMockLive(
            userOtpRows = userOtpRows,
            userOnboardRows = userOnboardRows,
            maybeUnexpectedError = maybeUserManagementRepositoryUnexpectedError,
            maybeServiceError = maybeUserManagementRepositoryServiceError,
          ),
          emailClientMockLive(maybeServiceError = maybeEmailClientServiceError),
          OtpGenerator.live,
          TimeProvider.liveSystemUTC,
          IDGenerator.uuidGeneratorLive,
          ZLayer.succeed(authenticationConfig),
          VerifyEmailValidator.live,
        )
        .zioValue
  }
}
