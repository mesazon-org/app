package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.UserForgotPasswordConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock as JavaClock, Instant}

class UserForgotPasswordServiceSpec extends ZWordSpecBase, RepositoryArbitraries {

  "UserForgotPasswordService" when {
    "forgotPassword" should {
      "successfully process forgot password request for the first time" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow)
        )

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe "otp-id-1"
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(expectedSendForgotPasswordEmailCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkOtpGenerator(expectedGenerateCalls = 1)
        checkUserActionAttemptRepository()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with resend otp within cooldown and not action attempts registered" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val instantNow                    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val javaClock                     = JavaClock.fixed(instantNow, java.time.ZoneOffset.UTC)
        val userOtpExpiresAtBufferSeconds = Random.nextLongBetween(0, 1000).zioValue
        val userOtpRow                    = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(
                userForgotPasswordConfig.otpResendCooldown.toSeconds + userOtpExpiresAtBufferSeconds
              )
            ),
          )

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          javaClock = javaClock,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpdateUserOtpCalls = 1,
        )
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkUserActionAttemptRepository(expectedGetAndIncreaseUserActionAttemptCalls = 1)
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with resend otp within cooldown and action attempts registered but under max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val instantNow                    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val javaClock                     = JavaClock.fixed(instantNow, java.time.ZoneOffset.UTC)
        val userOtpExpiresAtBufferSeconds = Random.nextLongBetween(0, 1000).zioValue
        val userOtpRow                    = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(
                userForgotPasswordConfig.otpResendCooldown.toSeconds + userOtpExpiresAtBufferSeconds
              )
            ),
          )

        val otpResetAttemptsMaxRetriesBuffer =
          Random.nextIntBetween(0, userForgotPasswordConfig.otpResetAttemptsMaxRetries).zioValue
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts =
              Attempts.assume(userForgotPasswordConfig.otpResetAttemptsMaxRetries - otpResetAttemptsMaxRetriesBuffer),
          )

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          javaClock = javaClock,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpdateUserOtpCalls = 1,
        )
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkUserActionAttemptRepository(expectedGetAndIncreaseUserActionAttemptCalls = 1)
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with resend otp within cooldown and action attempts registered at max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val instantNow                    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val javaClock                     = JavaClock.fixed(instantNow, java.time.ZoneOffset.UTC)
        val userOtpExpiresAtBufferSeconds = Random.nextLongBetween(0, 1000).zioValue
        val userOtpRow                    = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(
                userForgotPasswordConfig.otpResendCooldown.toSeconds + userOtpExpiresAtBufferSeconds
              )
            ),
          )

        val otpResetAttemptsMaxRetriesBuffer =
          Random.nextIntBetween(1, userForgotPasswordConfig.otpResetAttemptsMaxRetries).zioValue
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts =
              Attempts.assume(userForgotPasswordConfig.otpResetAttemptsMaxRetries + otpResetAttemptsMaxRetriesBuffer),
          )

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          javaClock = javaClock,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(expectedGetUserOtpByUserIDCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkUserActionAttemptRepository(expectedGetAndIncreaseUserActionAttemptCalls = 1)
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with otp being expired" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val instantNow                    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val javaClock                     = JavaClock.fixed(instantNow, java.time.ZoneOffset.UTC)
        val userOtpExpiresAtBufferSeconds =
          Random.nextLongBetween(1, 1000).zioValue
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(
                userForgotPasswordConfig.otpResendCooldown.toSeconds - userOtpExpiresAtBufferSeconds
              )
            ),
          )

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          javaClock = javaClock,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe "otp-id-1"
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(expectedSendForgotPasswordEmailCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkOtpGenerator(expectedGenerateCalls = 1)
        checkUserActionAttemptRepository()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with otp expired and action attempts registered at max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val instantNow                    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val javaClock                     = JavaClock.fixed(instantNow, java.time.ZoneOffset.UTC)
        val userOtpExpiresAtBufferSeconds =
          Random.nextLongBetween(1, 1000).zioValue
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(
                userForgotPasswordConfig.otpResendCooldown.toSeconds - userOtpExpiresAtBufferSeconds
              )
            ),
          )

        val otpResetAttemptsMaxRetriesBuffer =
          Random.nextIntBetween(1, userForgotPasswordConfig.otpResetAttemptsMaxRetries).zioValue
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts =
              Attempts.assume(userForgotPasswordConfig.otpResetAttemptsMaxRetries + otpResetAttemptsMaxRetriesBuffer),
          )

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          javaClock = javaClock,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe "otp-id-1"
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(expectedSendForgotPasswordEmailCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkOtpGenerator(expectedGenerateCalls = 1)
        checkUserActionAttemptRepository()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with no user details found" in new TestContext {
        val email = arbitrarySample[Email]

        val request = smithy.ForgotPasswordPostRequest(email = email.value)

        val userForgotPasswordService = buildForgotPasswordService()

        val forgotPasswordPostResponse = userForgotPasswordService.forgotPasswordPost(request).zioValue

        forgotPasswordPostResponse.otpID shouldBe "id-1"
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkIDGenerator(expectedGenerateCalls = 1)
        checkUserOtpRepository()
        checkEmailClient()
        checkTimeProvider()
        checkOtpGenerator()
        checkUserActionAttemptRepository()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "fail with ValidationError when request validation fails" in new TestContext {
        val request = smithy.ForgotPasswordPostRequest(email = "invalid-email")

        val userForgotPasswordService = buildForgotPasswordService()

        val serviceError = userForgotPasswordService.forgotPasswordPost(request).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError.asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(
            invalidFields = Seq(
              InvalidFieldError(
                fieldName = "email",
                errorMessage = "Invalid email format: [invalid-email], error: [null]",
                invalidValue = "invalid-email",
              )
            )
          )

        checkUserDetailsRepository()
        checkIDGenerator()
        checkUserOtpRepository()
        checkEmailClient()
        checkTimeProvider()
        checkOtpGenerator()
        checkUserActionAttemptRepository()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "fail with FailedOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val request = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow)
        )

        val serviceError = userForgotPasswordService.forgotPasswordPost(request).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
          )

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkIDGenerator()
        checkUserOtpRepository()
        checkEmailClient()
        checkTimeProvider()
        checkOtpGenerator()
        checkUserActionAttemptRepository()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "fail with UnexpectedError when user details repository returns service error" in new TestContext {
        val email = arbitrarySample[Email]

        val request = smithy.ForgotPasswordPostRequest(email = email.value)

        val userForgotPasswordService = buildForgotPasswordService(
          userDetailsRepositoryServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("DB connection error"))
        )

        val serviceError = userForgotPasswordService.forgotPasswordPost(request).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("DB connection error")

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkIDGenerator()
        checkUserOtpRepository()
        checkEmailClient()
        checkTimeProvider()
        checkOtpGenerator()
        checkUserActionAttemptRepository()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "forgotPasswordVerifyOTPPost" when {
        "successfully verify otp and reset password" in new TestContext {}
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserActionAttemptRepositoryMock,
        UserOtpRepositoryMock,
        UserCredentialsRepositoryMock,
        PasswordServiceMock,
        UserTokenRepositoryMock,
        EmailClientMock,
        JwtServiceMock,
        TimeProviderMock,
        IDGeneratorMock,
        OtpGeneratorMock {

    val userForgotPasswordConfig = UserForgotPasswordConfig(
      otpExpiresAtOffset = Duration.fromSeconds(60),
      otpResendCooldown = Duration.fromSeconds(20),
      otpResetAttemptsMaxRetries = 3,
      sendForgotPasswordEmailMaxRetries = 3,
      sendForgotPasswordEmailRetryDelay = Duration.fromMillis(100),
    )

    def buildForgotPasswordService(
        javaClock: JavaClock = JavaClock.systemUTC(),
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
        userTokenRows: Map[TokenID, UserTokenRow] = Map.empty,
        userCredentialsRows: Map[UserID, UserCredentialsRow] = Map.empty,
        userActionAttemptRowOpt: Option[UserActionAttemptRow] = None,
        passwordServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userActionAttemptRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userOtpRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userCredentialsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        emailClientServiceErrorOpt: Option[ServiceError] = None,
        jwtServiceErrorOpt: Option[ServiceError] = None,
    ): smithy.UserForgotPasswordService[ServiceTask] =
      ZIO
        .service[smithy.UserForgotPasswordService[ServiceTask]]
        .provide(
          otpGeneratorMockLive(),
          idGeneratorMockLive(),
          userTokenRepositoryMockLive(userTokenRows = userTokenRows),
          timeProviderMockLive(javaClock),
          UserForgotPasswordService.local,
          EmailDomainValidator.live,
          ForgotPasswordVerifyOTPPostRequestServiceValidator.live,
          ForgotPasswordPostRequestServiceValidator.live,
          ZLayer.succeed(userForgotPasswordConfig),
          jwtServiceMockLive(maybeServiceError = jwtServiceErrorOpt),
          userDetailsRepositoryMockLive(
            userDetailsRows = userDetailsRows,
            serviceErrorOpt = userDetailsRepositoryServiceErrorOpt,
          ),
          userActionAttemptRepositoryMockLive(
            userActionAttemptRowOpt = userActionAttemptRowOpt,
            serviceErrorOpt = userActionAttemptRepositoryServiceErrorOpt,
          ),
          userOtpRepositoryMockLive(
            userOtpRows = userOtpRows,
            serviceErrorOpt = userOtpRepositoryServiceErrorOpt,
          ),
          userCredentialsRepositoryMockLive(
            userCredentialsRows = userCredentialsRows,
            serviceErrorOpt = userCredentialsRepositoryServiceErrorOpt,
          ),
          passwordServiceMockLive(
            serviceErrorOpt = passwordServiceErrorOpt
          ),
          emailClientMockLive(
            maybeServiceError = emailClientServiceErrorOpt
          ),
        )
        .zioValue
  }
}
