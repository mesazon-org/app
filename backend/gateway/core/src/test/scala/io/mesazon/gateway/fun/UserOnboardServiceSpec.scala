package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.{PhoneNumberValidatorConfig, UserOnboardConfig}
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{ServiceTask, UserOnboardService}
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.PhoneNumberDomainValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.gateway.{smithy, Mocks}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.Instant

class UserOnboardServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries {

  "UserOnboardService" when {
    "onboardPassword" should {
      "successfully onboard password for user in email verified stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = OnboardStage.EmailVerified)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val onboardPasswordPostResponse =
          userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioValue

        onboardPasswordPostResponse.onboardStage.name shouldBe "PASSWORD_PROVIDED"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserCredentialsRepository(
          expectedInsertUserCredentialsCalls = 1
        )
        checkEmailClient(
          expectedSendWelcomeEmailCalls = 1
        )
        checkPasswordService(
          expectedHashPasswordCalls = 1
        )
        checkTwilioClient()
        checkUserOtpRepository()
      }

      "successfully onboard password for user but retry sending welcome email when fails to send email" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = OnboardStage.EmailVerified)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          emailClientServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("Email client error")),
        )

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val onboardPasswordPostResponse =
          userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioValue

        onboardPasswordPostResponse.onboardStage.name shouldBe "PASSWORD_PROVIDED"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserCredentialsRepository(
          expectedInsertUserCredentialsCalls = 1
        )
        checkEmailClient(
          expectedSendWelcomeEmailCalls = userOnboardConfig.sendWelcomeEmailMaxRetries + 1
        )
        checkPasswordService(
          expectedHashPasswordCalls = 1
        )
        checkTwilioClient()
        checkUserOtpRepository()
      }

      "fail with Unauthorized when onboard password for user not in email verified stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(
            userID = authedUser.userID,
            onboardStage =
              Random.shuffle(OnboardStage.values.diff(List(OnboardStage.EmailVerified)).toList).zioValue.head,
          )

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val serviceError = userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardPasswordStages,
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
        checkUserOtpRepository()
      }

      "fail with ValidationError when onboard password with invalid password" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = OnboardStage.EmailVerified)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
          .copy(password = "short")

        val serviceError = userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(invalidFields =
            List(
              InvalidFieldError(
                "password",
                "Should match ^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$",
                Seq("short"),
              )
            )
          )

        checkUserDetailsRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
        checkUserOtpRepository()
      }

      "fail with InternalServerError when user details does not exist" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map.empty,
        )

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val serviceError = userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UserNotFoundError] shouldBe ServiceError.InternalServerError
          .UserNotFoundError(
            s"User details not found for userID: [${authedUser.userID}]"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
        checkUserOtpRepository()
      }

      "fail with InternalServer Error when password service fails to hash password" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = OnboardStage.EmailVerified)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          passwordServiceServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("Password service error")),
        )

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val serviceError = userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            "Password service error"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService(
          expectedHashPasswordCalls = 1
        )
        checkTwilioClient()
        checkUserOtpRepository()
      }
    }

    "onboardDetails" should {
      "successfully onboard details for user in valid onboard stage" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse.onboardStage.name shouldBe "PHONE_VERIFICATION"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient(
          expectedSendOtpCalls = 1
        )
      }

      "successfully onboard details for user with non expired otp" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val instantNow = Instant.now()
        val buffer     = Random.nextIntBetween(1, 300).zioValue
        val expiresAt  =
          ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds + buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse.onboardStage.name shouldBe "PHONE_VERIFICATION"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "successfully onboard details for user with expired otp" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val instantNow = Instant.now()
        val buffer     = Random.nextIntBetween(0, 300).zioValue
        val expiresAt  =
          ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds - buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse.onboardStage.name shouldBe "PHONE_VERIFICATION"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient(
          expectedSendOtpCalls = 1
        )
      }

      "fail with Unauthorized when onboardStage is not valid" in new TestContext {
        val authedUser          = arbitrarySample[AuthedUser]
        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.onboardDetailsStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageInvalid)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardDetailsStages,
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with ValidationError when is request is invalid" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
          .copy(fullName = "")

        val serviceError =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(
            invalidFields = List(
              InvalidFieldError(
                fieldName = "fullName",
                errorMessage = "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                invalidValues = Seq(""),
              )
            )
          )

        checkUserDetailsRepository()
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with InternalServerError when user details does not exist" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map.empty,
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UserNotFoundError] shouldBe ServiceError.InternalServerError
          .UserNotFoundError(
            s"User details not found for userID: [${authedUser.userID}]"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail and retry with InternalServerError when twilio client sms sent otp fails" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          twilioClientServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("")),
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("")

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1,
          expectedUpsertUserOtpCalls = 1,
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient(
          expectedSendOtpCalls = userOnboardConfig.sendPhoneVerificationOtpMaxRetries + 1
        )
      }

      "fail with InternalServerError when repository fail" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRepositoryServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("")),
        )

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("")

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }
    }

    "onboardVerifyPhoneNumber" should {
      "successfully onboard verify phone number for user in valid onboard stage" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val instantNow = Instant.now()
        val buffer     = Random.nextIntBetween(1, 300).zioValue
        val expiresAt  =
          ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds + buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          userOtpRow.otp.value,
        )

        val onboardVerifyPhoneNumberResponse =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioValue

        onboardVerifyPhoneNumberResponse.onboardStage.name shouldBe "PHONE_VERIFIED"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserOtpRepository(
          expectedGetUserOtpCalls = 1,
          expectedDeleteUserOtpCalls = 1,
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with BadRequest when request is invalid" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser
        )

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          .copy(otp = "invalid")

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(invalidFields =
            List(
              InvalidFieldError("otp", "Should match ^[A-Z0-9]{6}$", Seq("invalid"))
            )
          )

        checkUserDetailsRepository()
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with Unauthorized when onboard stage is not valid" in new TestContext {
        val authedUser          = arbitrarySample[AuthedUser]
        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.onboardVerifyPhoneNumberStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageInvalid)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardVerifyPhoneNumberStages,
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with Unauthorized when otp is expired" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val instantNow = Instant.now()
        val buffer     = Random.nextIntBetween(1, 300).zioValue
        val expiresAt  =
          ExpiresAt(instantNow.minusSeconds(buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          userOtpRow.otp.value,
        )

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"Wrong or expired OTP provided for otpID: [${userOtpRow.otpID}]"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository(
          expectedGetUserOtpCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with Unauthorized when otp does not exist" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map.empty,
        )

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"No OTP found for otpID: [${onboardVerifyPhoneNumberPostRequest.otpID}]"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository(
          expectedGetUserOtpCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with Unauthorized when otp is wrong" in new TestContext {
        val authedUser        = arbitrarySample[AuthedUser]
        val onboardStageValid = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow    = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStageValid)

        val instantNow = Instant.now()
        val buffer     = Random.nextIntBetween(1, 300).zioValue
        val expiresAt  =
          ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds + buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          "123ABC",
        )

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"Wrong or expired OTP provided for otpID: [${userOtpRow.otpID}]"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository(
          expectedGetUserOtpCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with InternalServerError when user details does not exist" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map.empty,
        )

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
        serviceError.asInstanceOf[
          ServiceError.InternalServerError.UserNotFoundError
        ] shouldBe ServiceError.InternalServerError
          .UserNotFoundError(
            s"User details not found for userID: [${authedUser.userID}]"
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with InternalServerError when repository fails" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRepositoryServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("")),
        )

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.asInstanceOf[
          ServiceError.InternalServerError.UnexpectedError
        ] shouldBe ServiceError.InternalServerError.UnexpectedError("")

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserOtpRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserCredentialsRepositoryMock,
        UserOtpRepositoryMock,
        EmailClientMock,
        PasswordServiceMock,
        TwilioClientMock {

    val userOnboardConfig = UserOnboardConfig(
      otpPhoneVerificationExpiresAtOffset = 10.seconds,
      otpPhoneVerificationResendCooldown = 2.seconds,
      sendWelcomeEmailMaxRetries = 3,
      sendWelcomeEmailRetryDelay = 1.millisecond,
      sendPhoneVerificationOtpMaxRetries = 5,
      sendPhoneVerificationOtpRetryDelay = 1.millisecond,
    )

    def buildUserOnboardServiceLive(
        authedUser: AuthedUser,
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userCredentialsRows: Map[UserID, UserCredentialsRow] = Map.empty,
        userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
        userOtpRepositoryServiceErrorOpt: Option[ServiceError] = None,
        passwordServiceServiceErrorOpt: Option[ServiceError] = None,
        emailClientServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userCredentialsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        twilioClientServiceErrorOpt: Option[ServiceError] = None,
    ): smithy.UserOnboardService[ServiceTask] =
      ZIO
        .service[smithy.UserOnboardService[ServiceTask]]
        .provide(
          UserOnboardService.local,
          OtpGenerator.live,
          TimeProvider.liveSystemUTC,
          PhoneNumberUtil.live,
          OnboardPasswordPostRequestServiceValidator.live,
          OnboardDetailsPostRequestServiceValidator.live,
          OnboardVerifyPhoneNumberPostRequestServiceValidator.live,
          ZLayer.succeed(
            PhoneNumberValidatorConfig(
              supportedPhoneRegions = Set("CY", "GB")
            )
          ),
          PhoneNumberDomainValidator.live,
          twilioClientMockLive(
            maybeServiceError = twilioClientServiceErrorOpt
          ),
          passwordServiceMockLive(
            serviceErrorOpt = passwordServiceServiceErrorOpt
          ),
          Mocks.authStateLive(authedUser),
          userDetailsRepositoryMockLive(
            userDetailsRows = userDetailsRows,
            serviceErrorOpt = userDetailsRepositoryServiceErrorOpt,
          ),
          userOtpRepositoryMockLive(
            userOtpRows = userOtpRows,
            serviceErrorOpt = userOtpRepositoryServiceErrorOpt,
          ),
          userCredentialsRepositoryMockLive(
            userCredentialsRows = userCredentialsRows,
            serviceErrorOpt = userCredentialsRepositoryServiceErrorOpt,
          ),
          emailClientMockLive(
            maybeServiceError = emailClientServiceErrorOpt
          ),
          ZLayer.succeed(userOnboardConfig),
        )
        .zioValue
  }
}
