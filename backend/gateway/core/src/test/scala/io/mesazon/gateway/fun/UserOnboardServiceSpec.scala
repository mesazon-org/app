package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.clients.{EmailClient, TwilioClient}
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.{UserCredentialsRepository, UserDetailsRepository, UserOtpRepository}
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.PhoneNumberDomainValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserOnboardServiceSpec
    extends ZWordSpecBase,
      SmithyArbitraries,
      UserOnboardSmithyArbitraries,
      RepositoryArbitraries {

  "UserOnboardService" when {
    "onboardPasswordPost" should {
      "successfully onboard password for user in email verified stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardPasswordStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val password     = arbitrarySample[Password]
        val passwordHash = arbitrarySample[PasswordHash]

        val userDetailsRowUpdated = userDetailsRow.copy(onboardStage = OnboardStage.PasswordProvided)

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          passwordServiceMock.hashPassword.expects(password).returningZIO(passwordHash).once(),
          userCredentialsRepositoryMock.insertUserCredentials
            .expects(userDetailsRow.userID, passwordHash)
            .returningZIOUnit
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userDetailsRow.userID, OnboardStage.PasswordProvided, None, None)
            .returningZIO(userDetailsRowUpdated)
            .once(),
          emailClientMock.sendWelcomeEmail
            .expects(userDetailsRow.email)
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
          .copy(password = password.value)

        val onboardPasswordPostResponse =
          userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioValue

        onboardPasswordPostResponse shouldBe smithy.OnboardPasswordPostResponse(smithy.OnboardStage.PASSWORD_PROVIDED)
      }

      "successfully onboard password for user but retry sending welcome email when fails to send email" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardPasswordStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val password     = arbitrarySample[Password]
        val passwordHash = arbitrarySample[PasswordHash]

        val userDetailsRowUpdated = userDetailsRow.copy(onboardStage = OnboardStage.PasswordProvided)

        val sendWelcomeEmailCounter = counterRef.zioValue

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          passwordServiceMock.hashPassword.expects(password).returningZIO(passwordHash).once(),
          userCredentialsRepositoryMock.insertUserCredentials
            .expects(userDetailsRow.userID, passwordHash)
            .returningZIOUnit
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userDetailsRow.userID, OnboardStage.PasswordProvided, None, None)
            .returningZIO(userDetailsRowUpdated)
            .once(),
          emailClientMock.sendWelcomeEmail
            .expects(userDetailsRow.email)
            .returns(
              sendWelcomeEmailCounter.incrementAndGet *> ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError("Email service error")
              )
            )
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
          .copy(password = password.value)

        val onboardPasswordPostResponse =
          userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioValue

        onboardPasswordPostResponse shouldBe smithy.OnboardPasswordPostResponse(smithy.OnboardStage.PASSWORD_PROVIDED)

        sendWelcomeEmailCounter.get.zioValue shouldBe userOnboardConfig.sendWelcomeEmailMaxRetries + 1
      }

      "fail with InvalidOnboardStage when onboard password for user not in email verified stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(
            userID = authedUser.userID,
            onboardStage =
              Random.shuffle(OnboardStage.values.diff(List(OnboardStage.EmailVerified)).toList).zioValue.head,
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val serviceError = userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardPasswordStages,
          )
      }

      "fail with ValidationError when onboard password with invalid password" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once()
        )

        val userOnboardService = buildUserOnboardServiceLive()

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
      }

      "fail with UnexpectedError when user details does not exist" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

        val serviceError = userOnboardService.onboardPasswordPost(onboardPasswordPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"User details not found for userID: [${authedUser.userID}]"
          )
      }
    }

    "onboardDetailsPost" should {
      "successfully onboard details for user in valid onboard stage" in new TestContext {
        val authedUser   = arbitrarySample[AuthedUser]
        val onboardStage = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds)),
          )

        val fullName    = arbitrarySample[FullName]
        val phoneNumber = arbitrarySample[PhoneNumber]

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
          .copy(
            fullName = fullName.value,
            phoneNumber = smithy.PhoneNumberRequest(
              phoneNumber.phoneNationalNumber.value,
              phoneNumber.phoneCountryCode.value,
            ),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(None)
            .once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(authedUser.userID, OtpType.PhoneVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(
              authedUser.userID,
              OnboardStage.PhoneVerification,
              Some(fullName),
              Some(phoneNumber),
            )
            .returningZIO(userDetailsRow)
            .once(),
          twilioClientMock.sendOtpSms
            .expects(
              phoneNumber.phoneNumberE164,
              userOtpRow.otp,
            )
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse shouldBe smithy.OnboardDetailsPostResponse(
          smithy.OnboardStage.PHONE_VERIFICATION,
          userOtpRow.otpID.value,
          userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds,
        )
      }

      "successfully onboard details without sending the OTP SMS when isDev is true" in new TestContext {
        val authedUser   = arbitrarySample[AuthedUser]
        val onboardStage = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds)),
          )

        val fullName    = arbitrarySample[FullName]
        val phoneNumber = arbitrarySample[PhoneNumber]

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
          .copy(
            fullName = fullName.value,
            phoneNumber = smithy.PhoneNumberRequest(
              phoneNumber.phoneNationalNumber.value,
              phoneNumber.phoneCountryCode.value,
            ),
          )

        // No twilioClientMock expectation: in dev mode the OTP SMS must not be sent
        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(None)
            .once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(authedUser.userID, OtpType.PhoneVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(
              authedUser.userID,
              OnboardStage.PhoneVerification,
              Some(fullName),
              Some(phoneNumber),
            )
            .returningZIO(userDetailsRow)
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive(isDev = true)

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse shouldBe smithy.OnboardDetailsPostResponse(
          smithy.OnboardStage.PHONE_VERIFICATION,
          userOtpRow.otpID.value,
          userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds,
        )
      }

      "successfully onboard details for user with non expired otp" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val buffer    = Random.nextIntBetween(1, 300).zioValue
        val expiresAt =
          ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds + buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse shouldBe smithy.OnboardDetailsPostResponse(
          smithy.OnboardStage.PHONE_VERIFICATION,
          userOtpRow.otpID.value,
          expiresAt.value.getEpochSecond - instantNow.getEpochSecond,
        )
      }

      "successfully onboard details for user with expired otp" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val buffer    = Random.nextIntBetween(1, 300).zioValue
        val expiresAt =
          ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds - buffer))

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = expiresAt,
          )

        val fullName    = arbitrarySample[FullName]
        val phoneNumber = arbitrarySample[PhoneNumber]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(None)
            .once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(
              authedUser.userID,
              OtpType.PhoneVerification,
              userOtpRow.otp,
              ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds)),
            )
            .returningZIO(userOtpRow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(
              authedUser.userID,
              OnboardStage.PhoneVerification,
              Some(fullName),
              Some(phoneNumber),
            )
            .returningZIO(userDetailsRow)
            .once(),
          twilioClientMock.sendOtpSms
            .expects(
              phoneNumber.phoneNumberE164,
              userOtpRow.otp,
            )
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
          .copy(
            fullName = fullName.value,
            phoneNumber = smithy.PhoneNumberRequest(
              phoneNumber.phoneNationalNumber.value,
              phoneNumber.phoneCountryCode.value,
            ),
          )

        val onboardDetailsPostResponse =
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioValue

        onboardDetailsPostResponse.onboardStage.name shouldBe "PHONE_VERIFICATION"
      }

      "fail with InvalidOnboardStage when onboardStage is not valid" in new TestContext {
        val authedUser   = arbitrarySample[AuthedUser]
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.onboardDetailsStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardDetailsStages,
          )
      }

      "fail with ValidationError when is request is invalid" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once()
        )

        val userOnboardService = buildUserOnboardServiceLive()

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
                errorMessage =
                  "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255",
                invalidValues = Seq(""),
              )
            )
          )
      }

      "fail with UnexpectedError when user details does not exist" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"User details not found for userID: [${authedUser.userID}]"
          )
      }

      "fail and retry with UnexpectedError when mobile client fails to sent otp sms" in new TestContext {
        val authedUser   = arbitrarySample[AuthedUser]
        val onboardStage = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds)),
          )

        val fullName    = arbitrarySample[FullName]
        val phoneNumber = arbitrarySample[PhoneNumber]

        val sendOtpSmsCounter = counterRef.zioValue

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(None)
            .once(),
          (() => otpGeneratorMock.generateOtp).expects().returningZIO(userOtpRow.otp).once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(authedUser.userID, OtpType.PhoneVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(
              authedUser.userID,
              OnboardStage.PhoneVerification,
              Some(fullName),
              Some(phoneNumber),
            )
            .returningZIO(userDetailsRow)
            .once(),
          twilioClientMock.sendOtpSms
            .expects(
              phoneNumber.phoneNumberE164,
              userOtpRow.otp,
            )
            .returns(
              sendOtpSmsCounter.incrementAndGet *> ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError("Failed to send sms")
              )
            )
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
          .copy(
            fullName = fullName.value,
            phoneNumber = smithy.PhoneNumberRequest(
              phoneNumber.phoneNationalNumber.value,
              phoneNumber.phoneCountryCode.value,
            ),
          )

        val serviceError = userOnboardService.onboardDetailsPost(onboardDetailsPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Failed to send sms")

        sendOtpSmsCounter.get.zioValue shouldBe userOnboardConfig.sendPhoneVerificationOtpMaxRetries + 1
      }
    }

    "onboardVerifyPhoneNumberPost" should {
      "successfully onboard verify phone number for user in valid onboard stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val expiresAtBuffer = Random.nextIntBetween(1, 1000).zioValue
        val userOtpRow      = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds + expiresAtBuffer)
            ),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(authedUser.userID, OnboardStage.PhoneVerified, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          userOtpRow.otp.value,
        )

        val onboardVerifyPhoneNumberResponse =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioValue

        onboardVerifyPhoneNumberResponse shouldBe smithy.OnboardVerifyPhoneNumberPostResponse(
          smithy.OnboardStage.PHONE_VERIFIED
        )
      }

      "successfully onboard verify phone number with dev OTP not matching the stored OTP when isDev is true" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val expiresAtBuffer = Random.nextIntBetween(1, 1000).zioValue
        val userOtpRow      = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds + expiresAtBuffer)
            ),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(authedUser.userID, OnboardStage.PhoneVerified, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive(isDev = true)

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          DevOtp,
        )

        val onboardVerifyPhoneNumberResponse =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioValue

        onboardVerifyPhoneNumberResponse shouldBe smithy.OnboardVerifyPhoneNumberPostResponse(
          smithy.OnboardStage.PHONE_VERIFIED
        )
      }

      "fail with ValidationError when request is invalid" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once()
        )

        val userOnboardService = buildUserOnboardServiceLive()

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
      }

      "fail with InvalidOnboardStage when onboard stage is not valid" in new TestContext {
        val authedUser   = arbitrarySample[AuthedUser]
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.onboardVerifyPhoneNumberStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardVerifyPhoneNumberStages,
          )
      }

      "fail with OtpExpiredError when otp is expired" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val expiresAtbuffer = Random.nextIntBetween(1, 1000).zioValue
        val userOtpRow      = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = ExpiresAt(instantNow.minusSeconds(expiresAtbuffer)),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          userOtpRow.otp.value,
        )

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpExpiredError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpExpiredError] shouldBe ServiceError.UnauthorizedError
          .OtpExpiredError(
            s"Expired OTP provided for otpID: [${userOtpRow.otpID}]"
          )
      }

      "fail with UnexpectedError when otp does not exist" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val otpID = arbitrarySample[OtpID]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtp
            .expects(otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(None)
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          .copy(otpID = otpID.value)

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"No OTP found for otpID: [${onboardVerifyPhoneNumberPostRequest.otpID}]"
          )
      }

      "fail with OtpVerifyError when otp is wrong" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val expiresAtBuffer = Random.nextIntBetween(1, 300).zioValue
        val userOtpRow      = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds + expiresAtBuffer)
            ),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberPostRequest = smithy.OnboardVerifyPhoneNumberPostRequest(
          userOtpRow.otpID.value,
          "123ABC",
        )

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.OtpVerifyError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.OtpVerifyError] shouldBe ServiceError.BadRequestError
          .OtpVerifyError(
            s"Wrong OTP provided for otpID: [${userOtpRow.otpID}]"
          )
      }

      "fail with UnexpectedError when user details does not exist" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.asInstanceOf[
          ServiceError.InternalServerError.UnexpectedError
        ] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"User details not found for userID: [${authedUser.userID}]"
          )
      }
    }

    "onboardVerifyPhoneNumberGet" when {
      "successfully get phone verification otp for user in valid onboard stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt =
              ExpiresAt(Instant.now().plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds + 1)),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val onboardVerifyPhoneNumberGetResponse =
          userOnboardService.onboardVerifyPhoneNumberGet().zioValue

        onboardVerifyPhoneNumberGetResponse shouldBe smithy.OnboardVerifyPhoneNumberGetResponse(
          userOtpRow.otpID.value,
          userOtpRow.expiresAt.value.getEpochSecond - instantNow.getEpochSecond,
        )
      }

      "fail with UnexpectedError when otp does not exist for user" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(None)
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberGet().zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"No OTP found for userID: [${authedUser.userID}] and otpType: [${OtpType.PhoneVerification}]"
          )
      }

      "fail with OtpExpiredError when otp is expired for user" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = authedUser.userID,
            otpType = OtpType.PhoneVerification,
            expiresAt =
              ExpiresAt(Instant.now().plusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds - 1)),
          )

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(authedUser.userID, OtpType.PhoneVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, authedUser.userID, OtpType.PhoneVerification)
            .returningZIOUnit
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberGet().zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpExpiredError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpExpiredError] shouldBe ServiceError.UnauthorizedError
          .OtpExpiredError(
            s"OTP expired for otpID: [${userOtpRow.otpID}]"
          )
      }

      "fail with InvalidOnboardStage when onboard stage is not valid" in new TestContext {
        val authedUser   = arbitrarySample[AuthedUser]
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.onboardVerifyPhoneNumberStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
        )

        val userOnboardService = buildUserOnboardServiceLive()

        val serviceError =
          userOnboardService.onboardVerifyPhoneNumberGet().zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.InvalidOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.ForbiddenError.InvalidOnboardStage] shouldBe ServiceError.ForbiddenError
          .InvalidOnboardStage(
            userID = userDetailsRow.userID,
            onboardStageUser = userDetailsRow.onboardStage,
            onboardStagesAllowed = OnboardStage.onboardVerifyPhoneNumberStages,
          )
      }
    }
  }

  trait TestContext {

    val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    lazy val userOnboardConfig = UserOnboardConfig(
      isDev = false,
      otpPhoneVerificationExpiresAtOffset = 10.seconds,
      otpPhoneVerificationResendCooldown = 2.seconds,
      sendWelcomeEmailMaxRetries = 3,
      sendWelcomeEmailRetryDelay = 1.millisecond,
      sendPhoneVerificationOtpMaxRetries = 5,
      sendPhoneVerificationOtpRetryDelay = 1.millisecond,
    )

    val otpGeneratorMock              = mock[OtpGenerator]
    val timeProviderMock              = mock[TimeProvider]
    val twilioClientMock              = mock[TwilioClient]
    val passwordServiceMock           = mock[PasswordService]
    val authStateMock                 = mock[AuthState]
    val userDetailsRepositoryMock     = mock[UserDetailsRepository]
    val userOtpRepositoryMock         = mock[UserOtpRepository]
    val userCredentialsRepositoryMock = mock[UserCredentialsRepository]
    val emailClientMock               = mock[EmailClient]

    def buildUserOnboardServiceLive(isDev: Boolean = false): smithy.UserOnboardService[ServiceTask] =
      ZIO
        .service[smithy.UserOnboardService[ServiceTask]]
        .provide(
          UserOnboardService.local,
          PhoneNumberUtil.live,
          ZLayer.succeed(
            PhoneNumberValidatorConfig(
              supportedPhoneRegions = Set("GB", "CY")
            )
          ),
          PhoneNumberDomainValidator.live,
          UserOnboardRequestValidator.live,
          ZLayer.succeed(userOnboardConfig.copy(isDev = isDev)),
          ZLayer.succeed(otpGeneratorMock),
          ZLayer.succeed(timeProviderMock),
          ZLayer.succeed(twilioClientMock),
          ZLayer.succeed(passwordServiceMock),
          ZLayer.succeed(authStateMock),
          ZLayer.succeed(userDetailsRepositoryMock),
          ZLayer.succeed(userOtpRepositoryMock),
          ZLayer.succeed(userCredentialsRepositoryMock),
          ZLayer.succeed(emailClientMock),
        )
        .zioValue
  }
}
