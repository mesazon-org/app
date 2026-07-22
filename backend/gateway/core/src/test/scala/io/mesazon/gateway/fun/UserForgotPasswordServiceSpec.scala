package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.gateway.{OtpType, *}
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.UserForgotPasswordConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.EmailValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserForgotPasswordServiceSpec
    extends ZWordSpecBase,
      SmithyArbitraries,
      UserForgotPasswordSmithyArbitraries,
      RepositoryArbitraries,
      TokenArbitraries {

  "UserForgotPasswordService" when {
    "forgotPassword" should {
      "successfully process forgot password request for the first time" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds)),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(userDetailsRow.userID, OtpType.ForgotPassword)
            .returningZIO(None)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(userDetailsRow.userID, OtpType.ForgotPassword, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returnsZIOUnit
            .once(),
          emailClientMock.sendForgotPasswordEmail.expects(userDetailsRow.email, userOtpRow.otp).returningZIOUnit.once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
      }

      "successfully process forgot password request with resend otp within cooldown and not action attempts registered" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBufferSeconds = Random.nextLongBetween(1, 1000).zioValue
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

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts = Attempts.assume(1),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(userDetailsRow.userID, OtpType.ForgotPassword)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.ForgotPassword)
            .returningZIO(userActionAttemptRow)
            .once(),
          userOtpRepositoryMock.updateUserOtp
            .expects(
              userOtpRow.otpID,
              userDetailsRow.userID,
              OtpType.ForgotPassword,
              ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds)),
            )
            .returningZIO(userOtpRow)
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
      }

      "successfully process forgot password request with resend otp within cooldown and action attempts registered but under max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBufferSeconds = Random.nextLongBetween(1, 1000).zioValue
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

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(userDetailsRow.userID, OtpType.ForgotPassword)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.ForgotPassword)
            .returningZIO(userActionAttemptRow)
            .once(),
          userOtpRepositoryMock.updateUserOtp
            .expects(
              userOtpRow.otpID,
              userDetailsRow.userID,
              OtpType.ForgotPassword,
              ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds)),
            )
            .returningZIO(userOtpRow)
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
      }

      "successfully process forgot password request with resend otp within cooldown and action attempts registered at max retries" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBufferSeconds = Random.nextLongBetween(1, 1000).zioValue
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

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(userDetailsRow.userID, OtpType.ForgotPassword)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.ForgotPassword)
            .returningZIO(userActionAttemptRow)
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
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

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(userDetailsRow.userID, OtpType.ForgotPassword)
            .returningZIO(None)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(
              userDetailsRow.userID,
              OtpType.ForgotPassword,
              userOtpRow.otp,
              ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds)),
            )
            .returningZIO(userOtpRow)
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returnsZIOUnit
            .once(),
          emailClientMock.sendForgotPasswordEmail.expects(userDetailsRow.email, userOtpRow.otp).returningZIOUnit.once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
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

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(userDetailsRow.userID, OtpType.ForgotPassword)
            .returningZIO(None)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(
              userDetailsRow.userID,
              OtpType.ForgotPassword,
              userOtpRow.otp,
              ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds)),
            )
            .returningZIO(userOtpRow)
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returnsZIOUnit
            .once(),
          emailClientMock.sendForgotPasswordEmail.expects(userDetailsRow.email, userOtpRow.otp).returningZIOUnit.once(),
        )

        userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
          .expects(userDetailsRow.userID, ActionAttemptType.ForgotPassword)
          .returningZIO(userActionAttemptRow)
          .never()

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
      }

      "successfully process forgot password request with no user details found" in new TestContext {
        val email = arbitrarySample[Email]
        val otpID = arbitrarySample[OtpID]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(email)
            .returningZIO(None)
            .once(),
          (() => idGeneratorMock.generateID).expects().returnsZIO(otpID.value),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = email.value)

        val forgotPasswordPostResponse =
          userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioValue

        forgotPasswordPostResponse.otpID shouldBe otpID.value
        forgotPasswordPostResponse.otpExpiresInSeconds shouldBe userForgotPasswordConfig.otpExpiresAtOffset.toSeconds
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
      }

      "fail with InvalidOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once()
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordPostRequest = smithy.ForgotPasswordPostRequest(email = userDetailsRow.email.value)

        val serviceError = userForgotPasswordService.forgotPasswordPost(forgotPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
          )
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

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts = Attempts.assume(1),
          )

        val resetPasswordJwt = arbitrarySample[ResetPasswordJwt]

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, userOtpRow.userID, OtpType.ForgotPassword)
            .returningZIOUnit
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPassword)
            .returnsZIOUnit
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returnsZIOUnit
            .once(),
          jwtServiceMock.generateResetPasswordToken
            .expects(userDetailsRow.userID)
            .returningZIO(resetPasswordJwt)
            .once(),
          userTokenRepositoryMock.upsertUserToken
            .expects(
              resetPasswordJwt.tokenID,
              userDetailsRow.userID,
              TokenType.ResetPasswordToken,
              resetPasswordJwt.expiresAt,
              None,
            )
            .returnsZIOUnit
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val forgotPasswordVerifyOTPPostResponse =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioValue

        forgotPasswordVerifyOTPPostResponse.resetPasswordToken shouldBe resetPasswordJwt.resetPasswordToken.value
        forgotPasswordVerifyOTPPostResponse.resetPasswordTokenExpiresInSeconds shouldBe resetPasswordJwt.expiresIn.toSeconds
      }

      "successfully forgot password verify otp with dev OTP not matching the stored OTP when isDev is true" in new TestContext {
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
            attempts = Attempts.assume(1),
          )

        val resetPasswordJwt = arbitrarySample[ResetPasswordJwt]

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, userOtpRow.userID, OtpType.ForgotPassword)
            .returningZIOUnit
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPassword)
            .returnsZIOUnit
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returnsZIOUnit
            .once(),
          jwtServiceMock.generateResetPasswordToken
            .expects(userDetailsRow.userID)
            .returningZIO(resetPasswordJwt)
            .once(),
          userTokenRepositoryMock.upsertUserToken
            .expects(
              resetPasswordJwt.tokenID,
              userDetailsRow.userID,
              TokenType.ResetPasswordToken,
              resetPasswordJwt.expiresAt,
              None,
            )
            .returnsZIOUnit
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService(isDev = true)

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = DevOtp,
        )

        val forgotPasswordVerifyOTPPostResponse =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioValue

        forgotPasswordVerifyOTPPostResponse.resetPasswordToken shouldBe resetPasswordJwt.resetPasswordToken.value
        forgotPasswordVerifyOTPPostResponse.resetPasswordTokenExpiresInSeconds shouldBe resetPasswordJwt.expiresIn.toSeconds
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
      }

      "fail with UnexpectedError when user otp is missing" in new TestContext {
        val otpID = arbitrarySample[OtpID]
        val otp   = arbitrarySample[Otp]

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(otpID, OtpType.ForgotPassword)
            .returnsZIO(None)
            .once()
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = otpID.value,
          otp = otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"No OTP found for OTP ID [${otpID.value}] and OTP type [${OtpType.ForgotPassword}]"
          )
      }

      "fail with UnexpectedError when user details is missing for the user otp" in new TestContext {
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(otpType = OtpType.ForgotPassword)

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(None).once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"No user details found for userID: [${userOtpRow.userID}] and otpID: [${userOtpRow.otpID}]"
          )
      }

      "fail with InvalidOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
          )

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
          )
      }

      "fail with OtpVerifyError when wrong OTP provided" in new TestContext {
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
            attempts = Attempts.assume(1),
          )

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val otp = arbitrarySample[Otp]

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.OtpVerifyError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.OtpVerifyError] shouldBe ServiceError.BadRequestError
          .OtpVerifyError(
            s"Wrong OTP provided for OTP ID [${forgotPasswordVerifyOTPPostRequest.otpID}] and OTP type [${OtpType.ForgotPassword}]"
          )
      }

      "fail with OtpExpiredError when expired OTP provided" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpExpiresAtBuffer = Random.nextLongBetween(1, 1000).zioValue
        val userOtpRow             = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(instantNow.minusSeconds(userOtpExpiresAtBuffer)),
          )

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts = Attempts.assume(1),
          )

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, userOtpRow.userID, OtpType.ForgotPassword)
            .returningZIOUnit
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpExpiredError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpExpiredError] shouldBe ServiceError.UnauthorizedError
          .OtpExpiredError(
            s"Expired OTP provided for OTP ID [${forgotPasswordVerifyOTPPostRequest.otpID}] and OTP type [${OtpType.ForgotPassword}]"
          )
      }

      "fail with OtpVerifyError when verify action attempts has reached the limit" in new TestContext {
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

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.ForgotPassword)
            .returnsZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userOtpRow.userID, ActionAttemptType.ForgotPasswordVerifyOTP)
            .returningZIO(userActionAttemptRow)
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordVerifyOTPPostRequest = smithy.ForgotPasswordVerifyOTPPostRequest(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val serviceError =
          userForgotPasswordService.forgotPasswordVerifyOTPPost(forgotPasswordVerifyOTPPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.OtpVerifyError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.OtpVerifyError] shouldBe ServiceError.BadRequestError
          .OtpVerifyError(
            s"OTP validation attempts exceeded for OTP ID [${forgotPasswordVerifyOTPPostRequest.otpID}] and OTP type [${OtpType.ForgotPassword}]"
          )
      }
    }

    "forgotPasswordResetPost" should {
      "successfully forgot password reset" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val tokenID      = arbitrarySample[TokenID]
        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(tokenID = tokenID, userID = userDetailsRow.userID, tokenType = TokenType.ResetPasswordToken)

        val passwordHashNew = arbitrarySample[PasswordHash]

        val authedUserResetPassword = arbitrarySample[AuthedUserResetPassword]
          .copy(tokenID = tokenID, userID = userDetailsRow.userID)

        val resetPasswordToken = arbitrarySample[ResetPasswordToken]
        val passwordNew        = arbitrarySample[Password]

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]
          .copy(resetPasswordToken = resetPasswordToken.value, password = passwordNew.value)

        inSequence(
          jwtServiceMock.verifyResetPasswordToken
            .expects(resetPasswordToken)
            .returningZIO(authedUserResetPassword)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userTokenRepositoryMock.getUserToken
            .expects(authedUserResetPassword.tokenID, userDetailsRow.userID, TokenType.ResetPasswordToken)
            .returningZIO(Some(userTokenRow))
            .once(),
          passwordServiceMock.hashPassword
            .expects(passwordNew)
            .returningZIO(passwordHashNew)
            .once(),
          userCredentialsRepositoryMock.updateUserCredentials
            .expects(userDetailsRow.userID, passwordHashNew)
            .returnsZIOUnit
            .once(),
          userTokenRepositoryMock.deleteUserToken
            .expects(userTokenRow.tokenID, userDetailsRow.userID, TokenType.ResetPasswordToken)
            .returnsZIOUnit
            .once(),
          emailClientMock.sendPasswordChangeConfirmationEmail.expects(userDetailsRow.email).returnsZIOUnit.once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordResetPostResponse =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioEither

        assert(forgotPasswordResetPostResponse.isRight)
      }

      "successfully forgot password reset in scenario where email client fails to send password change confirmation email" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(userID = userDetailsRow.userID, tokenType = TokenType.ResetPasswordToken)

        val authedUserResetPassword = arbitrarySample[AuthedUserResetPassword]
          .copy(tokenID = userTokenRow.tokenID, userID = userDetailsRow.userID)

        val resetPasswordToken = arbitrarySample[ResetPasswordToken]

        val passwordNew     = arbitrarySample[Password]
        val passwordHashNew = arbitrarySample[PasswordHash]

        val sendPasswordChangeConfirmationEmailCounter = counterRef.zioValue

        inSequence(
          jwtServiceMock.verifyResetPasswordToken
            .expects(resetPasswordToken)
            .returningZIO(authedUserResetPassword)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userTokenRepositoryMock.getUserToken
            .expects(authedUserResetPassword.tokenID, userDetailsRow.userID, TokenType.ResetPasswordToken)
            .returningZIO(Some(userTokenRow))
            .once(),
          passwordServiceMock.hashPassword
            .expects(passwordNew)
            .returningZIO(passwordHashNew)
            .once(),
          userCredentialsRepositoryMock.updateUserCredentials
            .expects(userDetailsRow.userID, passwordHashNew)
            .returnsZIOUnit
            .once(),
          userTokenRepositoryMock.deleteUserToken
            .expects(userTokenRow.tokenID, userDetailsRow.userID, TokenType.ResetPasswordToken)
            .returnsZIOUnit
            .once(),
          emailClientMock.sendPasswordChangeConfirmationEmail
            .expects(userDetailsRow.email)
            .returns(
              sendPasswordChangeConfirmationEmailCounter.incrementAndGet *> ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError("Email service error")
              )
            )
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]
          .copy(resetPasswordToken = resetPasswordToken.value, password = passwordNew.value)

        val forgotPasswordResetPostResponse =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioEither

        assert(forgotPasswordResetPostResponse.isRight)

        sendPasswordChangeConfirmationEmailCounter.get.zioValue shouldBe userForgotPasswordConfig.sendPasswordChangeConfirmationEmailMaxRetries + 1
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
      }

      "fail with UnexpectedError Error when reset password token is missing" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val authedUserResetPassword = arbitrarySample[AuthedUserResetPassword]
          .copy(userID = userDetailsRow.userID)

        val resetPasswordToken = arbitrarySample[ResetPasswordToken]

        inSequence(
          jwtServiceMock.verifyResetPasswordToken
            .expects(resetPasswordToken)
            .returningZIO(authedUserResetPassword)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userTokenRepositoryMock.getUserToken
            .expects(authedUserResetPassword.tokenID, userDetailsRow.userID, TokenType.ResetPasswordToken)
            .returningZIO(None)
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]
          .copy(resetPasswordToken = resetPasswordToken.value)

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.asInstanceOf[
          ServiceError.InternalServerError.UnexpectedError
        ] shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Reset password token not found for tokenID: [${authedUserResetPassword.tokenID}], userID: [${authedUserResetPassword.userID}] and tokenType: [${TokenType.ResetPasswordToken}]"
        )
      }

      "fail with UnexpectedError when user details is missing for the reset password token" in new TestContext {
        val authedUserResetPassword = arbitrarySample[AuthedUserResetPassword]

        val resetPasswordToken = arbitrarySample[ResetPasswordToken]

        inSequence(
          jwtServiceMock.verifyResetPasswordToken
            .expects(resetPasswordToken)
            .returningZIO(authedUserResetPassword)
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(authedUserResetPassword.userID).returningZIO(None).once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]
          .copy(resetPasswordToken = resetPasswordToken.value)

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(s"No user details found for userID: [${authedUserResetPassword.userID.value}]")
      }

      "fail with InvalidOnboardStage when user is not in allowed onboard stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.forgotPasswordAllowedStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val authedUserResetPassword = arbitrarySample[AuthedUserResetPassword]
          .copy(userID = userDetailsRow.userID)

        val resetPasswordToken = arbitrarySample[ResetPasswordToken]

        inSequence(
          jwtServiceMock.verifyResetPasswordToken
            .expects(resetPasswordToken)
            .returningZIO(authedUserResetPassword)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
        )

        val userForgotPasswordService = buildUserForgotPasswordService()

        val forgotPasswordResetPostRequest = arbitrarySample[smithy.ForgotPasswordResetPostRequest]
          .copy(resetPasswordToken = resetPasswordToken.value)

        val serviceError =
          userForgotPasswordService.forgotPasswordResetPost(forgotPasswordResetPostRequest).zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
          )
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    lazy val userForgotPasswordConfig = UserForgotPasswordConfig(
      isDev = false,
      otpExpiresAtOffset = Duration.fromSeconds(60),
      otpResendCooldown = Duration.fromSeconds(20),
      otpResetAttemptsMaxRetries = 3,
      sendForgotPasswordEmailMaxRetries = 3,
      sendForgotPasswordEmailRetryDelay = Duration.fromMillis(100),
      otpVerifyAttemptsMaxRetries = 3,
      sendPasswordChangeConfirmationEmailMaxRetries = 3,
      sendPasswordChangeConfirmationEmailRetryDelay = Duration.fromMillis(100),
    )

    val otpGeneratorMock                = mock[OtpGenerator]
    val idGeneratorMock                 = mock[IDGenerator]
    val userTokenRepositoryMock         = mock[UserTokenRepository]
    val timeProviderMock                = mock[TimeProvider]
    val jwtServiceMock                  = mock[JwtService]
    val userDetailsRepositoryMock       = mock[UserDetailsRepository]
    val userActionAttemptRepositoryMock = mock[UserActionAttemptRepository]
    val userOtpRepositoryMock           = mock[UserOtpRepository]
    val userCredentialsRepositoryMock   = mock[UserCredentialsRepository]
    val passwordServiceMock             = mock[PasswordService]
    val emailClientMock                 = mock[EmailClient]

    def buildUserForgotPasswordService(isDev: Boolean = false): smithy.UserForgotPasswordService[ServiceTask] = ZIO
      .service[smithy.UserForgotPasswordService[ServiceTask]]
      .provide(
        UserForgotPasswordService.local,
        EmailValidator.live,
        UserForgotPasswordRequestValidator.live,
        ZLayer.succeed(userForgotPasswordConfig.copy(isDev = isDev)),
        ZLayer.succeed(otpGeneratorMock),
        ZLayer.succeed(idGeneratorMock),
        ZLayer.succeed(userTokenRepositoryMock),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(jwtServiceMock),
        ZLayer.succeed(userDetailsRepositoryMock),
        ZLayer.succeed(userActionAttemptRepositoryMock),
        ZLayer.succeed(userOtpRepositoryMock),
        ZLayer.succeed(userCredentialsRepositoryMock),
        ZLayer.succeed(passwordServiceMock),
        ZLayer.succeed(emailClientMock),
      )
      .zioValue
  }
}
