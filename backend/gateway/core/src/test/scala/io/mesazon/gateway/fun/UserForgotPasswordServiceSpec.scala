package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.UserForgotPasswordConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock as JavaClock, Instant}

class UserForgotPasswordServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries {

  "UserForgotPasswordService" when {
    "forgotPassword" should {
      "successfully process forgot password request for the first time" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow)
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(expectedSendForgotPasswordEmailCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkOtpGenerator(expectedGenerateCalls = 1)
        checkUserActionAttemptRepository(
          expectedDeleteUserActionAttemptCalls = 1
        )
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
        checkJwtService()
      }

      "successfully process forgot password request with resend otp within cooldown and not action attempts registered" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

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

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

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
        checkJwtService()
      }

      "successfully process forgot password request with resend otp within cooldown and action attempts registered but under max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

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

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

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
        checkJwtService()
      }

      "successfully process forgot password request with resend otp within cooldown and action attempts registered at max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

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

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

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
        checkJwtService()
      }

      "successfully process forgot password request with otp being expired" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

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

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(expectedSendForgotPasswordEmailCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkOtpGenerator(expectedGenerateCalls = 1)
        checkUserActionAttemptRepository(
          expectedDeleteUserActionAttemptCalls = 1
        )
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
        checkJwtService()
      }

      "successfully process forgot password request with otp expired and action attempts registered at max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

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

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds

        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkEmailClient(expectedSendForgotPasswordEmailCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkOtpGenerator(expectedGenerateCalls = 1)
        checkUserActionAttemptRepository(
          expectedDeleteUserActionAttemptCalls = 1
        )
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserTokenRepository()
      }

      "successfully process forgot password request with no user details found" in new TestContext {
        val email = arbitrarySample[Email]

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

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
        checkJwtService()
      }

      "fail with ValidationError when request validation fails" in new TestContext {
        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = "invalid-email")

        val serviceError = userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioError

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
        checkJwtService()
      }

      "fail with FailedOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow)
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val serviceError = userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioError

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
        checkJwtService()
      }

      "fail with UnexpectedError when user details repository returns service error" in new TestContext {
        val email = arbitrarySample[Email]

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRepositoryServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("DB connection error"))
        )

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = email.value)

        val serviceError = userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioError

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
        checkJwtService()
      }
    }

    "forgotPasswordVerifyOTPPost" should {
      "successfully forgot password verify otp" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBuffer = Random.nextLongBetween(1, 1000).zioValue
        val userOtpRow             = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(instantNow.plusSeconds(userOtpExpiresAtBuffer)),
          )

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val forgotPasswordVerifyOTPPostResponse =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioValue

        forgotPasswordVerifyOTPPostResponse.resetPasswordToken shouldBe "reset-password-token"
        forgotPasswordVerifyOTPPostResponse.resetPasswordTokenExpiresInSeconds should be > 0L

        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1,
          expectedDeleteUserOtpCalls = 1,
        )
        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkJwtService(
          expectedGenerateResetPasswordTokenCalls = 1
        )
        checkUserTokenRepository(
          expectedUpsertUserTokenCalls = 1
        )
        checkTimeProvider(
          expectedInstantNowCalls = 1
        )
        checkUserActionAttemptRepository(
          expectedDeleteUserActionAttemptCalls = 2,
          expectedGetAndIncreaseUserActionAttemptCalls = 1,
        )
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with ValidationError when forgot password verify otp request validation fails" in new TestContext {
        val userForgotPasswordService = buildUserForgotPasswordService()

        val otpID = arbitrarySample[OtpID]

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = otpID.value,
          otp = "",
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError.asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(
            invalidFields = Seq(
              InvalidFieldError(
                fieldName = "otp",
                errorMessage = "Should match ^[A-Z0-9]{6}$",
                invalidValue = "",
              )
            )
          )

        checkUserOtpRepository()
        checkUserDetailsRepository()
        checkJwtService()
        checkUserTokenRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with OtpValidationError when user otp is missing" in new TestContext {
        val userForgotPasswordService = buildUserForgotPasswordService()

        val otpID = arbitrarySample[OtpID]
        val otp   = arbitrarySample[Otp]

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = otpID.value,
          otp = otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"No OTP found for OTP ID [${otpID.value}] and OTP type [${OtpType.ForgotPassword}]"
          )

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository()
        checkJwtService()
        checkUserTokenRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with UserNotFoundError when user details is missing for the user otp" in new TestContext {
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(otpType = OtpType.ForgotPassword)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow)
        )

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UserNotFoundError] shouldBe ServiceError.InternalServerError
          .UserNotFoundError(
            s"No user details found for userID: [${userOtpRow.userID}] and otpID: [${userOtpRow.otpID}]"
          )

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService()
        checkUserTokenRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with FailedOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
          )

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
          )

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService()
        checkUserTokenRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with OtpValidationError when wrong OTP provided" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBuffer = Random.nextLongBetween(1, 1000).zioValue
        val userOtpRow             = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(instantNow.plusSeconds(userOtpExpiresAtBuffer)),
          )

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val otp = arbitrarySample[Otp]

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"Wrong or expired OTP provided for OTP ID [${userOtpRow.otpID}] and OTP type [${OtpType.ForgotPassword}]"
          )

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkJwtService()
        checkUserTokenRepository()
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1
        )
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with OtpValidationError when expired OTP provided" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBuffer = Random.nextLongBetween(0, 1000).zioValue
        val userOtpRow             = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(instantNow.minusSeconds(userOtpExpiresAtBuffer)),
          )

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"Wrong or expired OTP provided for OTP ID [${userOtpRow.otpID}] and OTP type [${OtpType.ForgotPassword}]"
          )

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1
        )
        checkJwtService()
        checkUserTokenRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with OtpValidationError when verify action attempts has reached the limit" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBuffer = Random.nextLongBetween(1, 1000).zioValue
        val userOtpRow             = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(instantNow.plusSeconds(userOtpExpiresAtBuffer)),
          )

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts = Attempts.assume(userForgotPasswordConfig.otpVerifyAttemptsMaxRetries + 1),
          )

        val userForgotPasswordService = buildUserForgotPasswordService(
          clock = clockFixed,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"OTP validation attempts exceeded for OTP ID [${userOtpRow.otpID}] and OTP type [${OtpType.ForgotPassword}]"
          )

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1
        )
        checkTimeProvider()
        checkJwtService()
        checkUserTokenRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }

      "fail with UnexpectedError when user otp repository returns service error" in new TestContext {
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(otpType = OtpType.ForgotPassword)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
          userOtpRepositoryServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("DB connection error")),
        )

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("DB connection error")

        checkUserOtpRepository(expectedGetUserOtpByOtpIDCalls = 1)
        checkUserDetailsRepository()
        checkJwtService()
        checkUserTokenRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkEmailClient()
        checkOtpGenerator()
        checkIDGenerator()
        checkUserCredentialsRepository()
        checkPasswordService()
      }
    }

    "forgotPasswordResetPost" should {
      "successfully forgot password reset" in new TestContext {
        val userID         = arbitrarySample[UserID]
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage, userID = userID)

        val passwordHashOld    = arbitrarySample[PasswordHash]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userID, passwordHash = passwordHashOld)

        val tokenID      = arbitrarySample[TokenID]
        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(tokenID = tokenID, userID = userID, tokenType = TokenType.ResetPasswordToken)

        val userForgotPasswordService = buildUserForgotPasswordService(
          jwtServiceUserIDOpt = Some(userID),
          jwtServiceTokenIDOpt = Some(tokenID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userTokenRows = Map(userTokenRow.tokenID -> userTokenRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]

        val forgotPasswordResetPostResponse =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioEither

        assert(forgotPasswordResetPostResponse.isRight)

        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService(expectedVerifyResetPasswordTokenCalls = 1)
        checkUserTokenRepository(
          expectedGetUserTokenCalls = 1,
          expectedDeleteUserTokenCalls = 1,
        )
        checkEmailClient(expectedSendPasswordChangeConfirmationEmailCalls = 1)
        checkUserCredentialsRepository(expectedUpdateUserCredentialsCalls = 1)
        checkPasswordService(expectedHashPasswordCalls = 1)
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "successfully forgot password reset in scenario where email client fails to send password change confirmation email" in new TestContext {
        val userID         = arbitrarySample[UserID]
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage, userID = userID)

        val passwordHashOld = arbitrarySample[PasswordHash]

        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userID, passwordHash = passwordHashOld)

        val tokenID      = arbitrarySample[TokenID]
        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(tokenID = tokenID, userID = userID, tokenType = TokenType.ResetPasswordToken)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userTokenRows = Map(userTokenRow.tokenID -> userTokenRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
          emailClientServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("Email service error")),
        )

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]

        val forgotPasswordResetPostResponse =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioEither

        assert(forgotPasswordResetPostResponse.isRight)

        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService(expectedVerifyResetPasswordTokenCalls = 1)
        checkUserTokenRepository(
          expectedGetUserTokenCalls = 1,
          expectedDeleteUserTokenCalls = 1,
        )
        checkEmailClient(expectedSendPasswordChangeConfirmationEmailCalls =
          userForgotPasswordConfig.sendPasswordChangeConfirmationEmailMaxRetries + 1
        )
        checkUserCredentialsRepository(expectedUpdateUserCredentialsCalls = 1)
        checkPasswordService(expectedHashPasswordCalls = 1)
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "fail with ValidationError when forgot password reset request validation fails" in new TestContext {
        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordResetPostRequest = smithy.ForgotPasswordResetPostRequest(
          resetPasswordToken = "",
          password = "short",
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(
            invalidFields = Seq(
              InvalidFieldError(
                fieldName = "resetPasswordToken",
                errorMessage = "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                invalidValue = "",
              ),
              InvalidFieldError(
                fieldName = "password",
                errorMessage =
                  "Should match ^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$",
                invalidValue = "short",
              ),
            )
          )

        checkUserDetailsRepository()
        checkJwtService()
        checkUserTokenRepository()
        checkEmailClient()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "fail with TokenMissing Error when reset password token is missing" in new TestContext {
        val userID         = arbitrarySample[UserID]
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage, userID = userID)

        val passwordHashOld    = arbitrarySample[PasswordHash]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userID, passwordHash = passwordHashOld)

        val tokenID = arbitrarySample[TokenID]

        val userForgotPasswordService = buildUserForgotPasswordService(
          jwtServiceUserIDOpt = Some(userID),
          jwtServiceTokenIDOpt = Some(tokenID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.TokenMissing.type]
        serviceError.asInstanceOf[
          ServiceError.UnauthorizedError.TokenMissing.type
        ] shouldBe ServiceError.UnauthorizedError.TokenMissing

        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService(expectedVerifyResetPasswordTokenCalls = 1)
        checkUserTokenRepository(expectedGetUserTokenCalls = 1)
        checkEmailClient()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "fail with UserNotFoundError when user details is missing for the reset password token" in new TestContext {
        val userID                    = arbitrarySample[UserID]
        val userForgotPasswordService = buildUserForgotPasswordService(
          jwtServiceUserIDOpt = Some(userID)
        )

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UserNotFoundError] shouldBe ServiceError.InternalServerError
          .UserNotFoundError(s"No user details found for userID: [${userID.value}]")

        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService(expectedVerifyResetPasswordTokenCalls = 1)
        checkUserTokenRepository()
        checkEmailClient()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "fail with FailedOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val userID       = arbitrarySample[UserID]
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage, userID = userID)

        val passwordHashOld    = arbitrarySample[PasswordHash]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userID, passwordHash = passwordHashOld)

        val tokenID      = arbitrarySample[TokenID]
        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(tokenID = tokenID, userID = userID, tokenType = TokenType.ResetPasswordToken)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userTokenRows = Map(userTokenRow.tokenID -> userTokenRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
          )

        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService(expectedVerifyResetPasswordTokenCalls = 1)
        checkUserTokenRepository()
        checkUserCredentialsRepository()
        checkPasswordService()
        checkEmailClient()
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "fail with UnexpectedError when user credentials repository returns service error" in new TestContext {
        val userID         = arbitrarySample[UserID]
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage, userID = userID)

        val passwordHashOld    = arbitrarySample[PasswordHash]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userID, passwordHash = passwordHashOld)

        val tokenID      = arbitrarySample[TokenID]
        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(tokenID = tokenID, userID = userID, tokenType = TokenType.ResetPasswordToken)

        val userForgotPasswordService = buildUserForgotPasswordService(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userTokenRows = Map(userTokenRow.tokenID -> userTokenRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
          userCredentialsRepositoryServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("DB connection error")),
        )

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]

        val serviceError = userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("DB connection error")

        checkUserDetailsRepository(expectedGetUserDetailsCalls = 1)
        checkJwtService(expectedVerifyResetPasswordTokenCalls = 1)
        checkUserTokenRepository(expectedGetUserTokenCalls = 1)
        checkUserCredentialsRepository(expectedUpdateUserCredentialsCalls = 1)
        checkPasswordService(expectedHashPasswordCalls = 1)
        checkEmailClient()
        checkUserOtpRepository()
        checkTimeProvider()
        checkUserActionAttemptRepository()
        checkOtpGenerator()
        checkIDGenerator()
      }

      "fail "
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

    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
    val clockFixed = JavaClock.fixed(instantNow, java.time.ZoneOffset.UTC)

    val userForgotPasswordConfig = UserForgotPasswordConfig(
      otpExpiresAtOffset = Duration.fromSeconds(60),
      otpResendCooldown = Duration.fromSeconds(20),
      otpResetAttemptsMaxRetries = 3,
      sendForgotPasswordEmailMaxRetries = 3,
      sendForgotPasswordEmailRetryDelay = Duration.fromMillis(100),
      otpVerifyAttemptsMaxRetries = 3,
      sendPasswordChangeConfirmationEmailMaxRetries = 3,
      sendPasswordChangeConfirmationEmailRetryDelay = Duration.fromMillis(100),
    )

    def buildUserForgotPasswordService(
        clock: JavaClock = JavaClock.systemUTC(),
        jwtServiceUserIDOpt: Option[UserID] = None,
        jwtServiceTokenIDOpt: Option[TokenID] = None,
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
          timeProviderMockLive(clock),
          UserForgotPasswordService.local,
          EmailDomainValidator.live,
          ForgotPasswordVerifyOTPPostRequestServiceValidator.live,
          ForgotPasswordPostRequestServiceValidator.live,
          ForgotPasswordResetPostRequestServiceValidator.live,
          ZLayer.succeed(userForgotPasswordConfig),
          jwtServiceMockLive(
            tokenIDOpt = jwtServiceTokenIDOpt,
            userIDOpt = jwtServiceUserIDOpt,
            maybeServiceError = jwtServiceErrorOpt,
          ),
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
