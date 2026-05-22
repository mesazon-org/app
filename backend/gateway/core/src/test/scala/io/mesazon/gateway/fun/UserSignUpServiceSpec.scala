package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserSignUpServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

  "userSignUpService" when {
    "signUpEmailPost" should {
      "successfully sign up a new user with valid email" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage
        )

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds)),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail.expects(userDetailsRow.email).returningZIO(None).once(),
          userDetailsRepositoryMock.insertUserDetails
            .expects(userDetailsRow.email, OnboardStage.EmailVerification)
            .returningZIO(userDetailsRow)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => otpGeneratorMock.generateOtp)
            .expects()
            .returningZIO(userOtpRow.otp)
            .once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(userDetailsRow.userID, OtpType.EmailVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          emailClientMock.sendEmailVerificationEmail.expects(userDetailsRow.email, userOtpRow.otp).returnsZIOUnit.once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val signUpEmailPostResponse = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse shouldBe smithy.SignUpEmailPostResponse(
          userOtpRow.otpID.value,
          userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
        )
      }

      "successfully re-sign up a user with sign up email stages with otp found and not expired" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val expiresAtBuffer      = Random.nextIntBetween(1, 1000).zioValue
        val userOtpRowNonExpired = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationResendCooldown.toSeconds + expiresAtBuffer)
            ),
          )

        val userOtpRowUpdated = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otp = userOtpRowNonExpired.otp,
            otpType = OtpType.EmailVerification,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds)),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
            )
            .returningZIO(Some(userOtpRowNonExpired))
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userDetailsRow.userID, OnboardStage.EmailVerification, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
              userOtpRowUpdated.otp,
              userOtpRowUpdated.expiresAt,
            )
            .returningZIO(userOtpRowUpdated)
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val signUpEmailPostResponse = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse shouldBe smithy.SignUpEmailPostResponse(
          userOtpRowUpdated.otpID.value,
          userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
        )
      }

      "successfully re-sign up a user with sign up email when no otp found" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(
              instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds)
            ),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
            )
            .returningZIO(None)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userDetailsRow.userID, OnboardStage.EmailVerification, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          (() => otpGeneratorMock.generateOtp)
            .expects()
            .returningZIO(userOtpRow.otp)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(userDetailsRow.userID, OtpType.EmailVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          emailClientMock.sendEmailVerificationEmail.expects(userDetailsRow.email, userOtpRow.otp).returnsZIOUnit.once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val signUpEmailPostResponse = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse shouldBe smithy.SignUpEmailPostResponse(
          userOtpRow.otpID.value,
          userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
        )
      }

      "successfully re-sign up a user with sign up email when otp found but expired" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val expiresAtBuffer   = Random.nextIntBetween(0, 1000).zioValue
        val userOtpRowExpired = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(
              instantNow
                .plusSeconds(userSignUpConfig.otpEmailVerificationResendCooldown.toSeconds - expiresAtBuffer)
            ),
          )

        val userOtpRowUpdated = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.getSeconds)),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
            )
            .returningZIO(Some(userOtpRowExpired))
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userDetailsRow.userID, OnboardStage.EmailVerification, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          (() => otpGeneratorMock.generateOtp)
            .expects()
            .returningZIO(userOtpRowUpdated.otp)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
              userOtpRowUpdated.otp,
              userOtpRowUpdated.expiresAt,
            )
            .returningZIO(userOtpRowUpdated)
            .once(),
          emailClientMock.sendEmailVerificationEmail
            .expects(userDetailsRow.email, userOtpRowUpdated.otp)
            .returnsZIOUnit
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val signUpEmailPostResponse = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse shouldBe smithy.SignUpEmailPostResponse(
          userOtpRowUpdated.otpID.value,
          userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
        )
      }

      "successfully fake sign up a user when onboard stage is not in sign up email stages" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.signUpEmailStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val otpID = arbitrarySample[OtpID]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
            )
            .returningZIO(None)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(otpID.value)
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val signUpEmailPostResponse = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse shouldBe smithy.SignUpEmailPostResponse(
          otpID.value,
          userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
        )
      }

      "fail with ValidationError when sign up a user with invalid email" in new TestContext {
        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = "invalid-email")

        val serviceError = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = Seq(
              ServiceError.BadRequestError.InvalidFieldError(
                fieldName = "email",
                errorMessage = "Invalid email format: [invalid-email], error: [null]",
                invalidValue = "invalid-email",
              )
            )
          )
      }

      "fail with UnexpectedError and retry sign up for already existing user when sending email when email client fails" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.getSeconds)),
          )

        val sendEmailVerificationEmailCounter = counterRef.zioValue

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userOtpRepositoryMock.getUserOtpByUserID
            .expects(
              userDetailsRow.userID,
              OtpType.EmailVerification,
            )
            .returningZIO(None)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userDetailsRow.userID, OnboardStage.EmailVerification, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          (() => otpGeneratorMock.generateOtp)
            .expects()
            .returningZIO(userOtpRow.otp)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(userDetailsRow.userID, OtpType.EmailVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          emailClientMock.sendEmailVerificationEmail
            .expects(userDetailsRow.email, userOtpRow.otp)
            .returns(
              sendEmailVerificationEmailCounter.incrementAndGet *> ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError("Email client error")
              )
            )
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val serviceError = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Email client error")

        sendEmailVerificationEmailCounter.get.zioValue shouldBe userSignUpConfig.sendEmailVerificationEmailMaxRetries + 1
      }

      "fail with UnexpectedError and retry sign up new user when sending email when email client fails" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            expiresAt =
              ExpiresAt(instantNow.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.getSeconds)),
          )

        val sendEmailVerificationEmailCounter = counterRef.zioValue

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail.expects(userDetailsRow.email).returningZIO(None).once(),
          userDetailsRepositoryMock.insertUserDetails
            .expects(userDetailsRow.email, OnboardStage.EmailVerification)
            .returningZIO(userDetailsRow)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => otpGeneratorMock.generateOtp)
            .expects()
            .returningZIO(userOtpRow.otp)
            .once(),
          userOtpRepositoryMock.upsertUserOtp
            .expects(userDetailsRow.userID, OtpType.EmailVerification, userOtpRow.otp, userOtpRow.expiresAt)
            .returningZIO(userOtpRow)
            .once(),
          emailClientMock.sendEmailVerificationEmail
            .expects(userDetailsRow.email, userOtpRow.otp)
            .returns(
              sendEmailVerificationEmailCounter.incrementAndGet *> ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError("Email client error")
              )
            )
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = userDetailsRow.email.value)

        val serviceError = userSignUpService.signUpEmailPost(signUpEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Email client error")
      }
    }

    "signUpVerifyEmailPost" should {
      "successfully verify email with valid otp" in new TestContext {
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = OnboardStage.EmailVerification)

        val expiresAtBuffer = Random.nextIntBetween(1, 1000).zioValue
        val userOtpRow      = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(instantNow.plusSeconds(expiresAtBuffer)),
          )

        val refreshJwt = arbitrarySample[RefreshJwt]
        val accessJwt  = arbitrarySample[AccessJwt]

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.EmailVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userDetailsRepositoryMock.updateUserDetails
            .expects(userOtpRow.userID, OnboardStage.EmailVerified, None, None)
            .returningZIO(userDetailsRow)
            .once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, userDetailsRow.userID, OtpType.EmailVerification)
            .returnsZIOUnit
            .once(),
          userTokenRepositoryMock.deleteAllUserTokens.expects(userDetailsRow.userID).returnsZIOUnit.once(),
          jwtServiceMock.generateAccessToken.expects(userDetailsRow.userID).returningZIO(accessJwt).once(),
          jwtServiceMock.generateRefreshToken.expects(userDetailsRow.userID).returningZIO(refreshJwt).once(),
          userTokenRepositoryMock.upsertUserToken
            .expects(
              refreshJwt.tokenID,
              userDetailsRow.userID,
              TokenType.RefreshToken,
              refreshJwt.expiresAt,
              None,
            )
            .returnsZIOUnit
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val signUpVerifyEmailPostResponse =
          userSignUpService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse shouldBe smithy.SignUpVerifyEmailPostResponse(
          accessTokenExpiresInSeconds = accessJwt.expiresIn.toSeconds,
          onboardStage = onboardStageFromDomainToSmithy(OnboardStage.EmailVerified),
          refreshToken = refreshJwt.refreshToken.value,
          accessToken = accessJwt.accessToken.value,
        )
      }

      "fail with ValidationError when verify email with invalid otp format" in new TestContext {
        val userSignUpService = buildUserSignUpServiceLive

        val otpID = arbitrarySample[OtpID]

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = otpID.value, otp = "invalid-otp")

        val serviceError = userSignUpService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(
            invalidFields = Seq(
              ServiceError.BadRequestError.InvalidFieldError(
                fieldName = "otp",
                errorMessage = "Should match ^[A-Z0-9]{6}$",
                invalidValue = "invalid-otp",
              )
            )
          )
      }

      "fail with UnexpectedError when verify email with non-existing otp" in new TestContext {
        val otpID = arbitrarySample[OtpID]

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(otpID, OtpType.EmailVerification)
            .returningZIO(None)
            .once()
        )
        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = otpID.value)

        val userSignUpService = buildUserSignUpServiceLive

        val serviceError = userSignUpService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"No otp found for otpID: [${signUpVerifyEmailPostRequest.otpID}] and otpType: [${OtpType.EmailVerification}]"
          )
      }

      "fail with FailedOnboardStage when verify email is send for user with no email verification stage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.signUpVerifyEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(userID = userDetailsRow.userID, otpType = OtpType.EmailVerification)

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.EmailVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val serviceError = userSignUpService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.signUpVerifyEmailStages,
          )
      }

      "fail with OtpExpiredError when verify email with expired otp" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpVerifyEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(instantNow.minusSeconds(10)),
          )

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.EmailVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userOtpRepositoryMock.deleteUserOtp
            .expects(userOtpRow.otpID, userDetailsRow.userID, OtpType.EmailVerification)
            .returnsZIOUnit
            .once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val serviceError = userSignUpService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpExpiredError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpExpiredError] shouldBe ServiceError.UnauthorizedError
          .OtpExpiredError(s"Expired OTP provided for otpID: [${userOtpRow.otpID}]")
      }

      "fail with OtpVerifyError when verify email with wrong otp" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signUpVerifyEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
          )

        inSequence(
          userOtpRepositoryMock.getUserOtpByOtpID
            .expects(userOtpRow.otpID, OtpType.EmailVerification)
            .returningZIO(Some(userOtpRow))
            .once(),
          userDetailsRepositoryMock.getUserDetails.expects(userOtpRow.userID).returningZIO(Some(userDetailsRow)).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
        )

        val userSignUpService = buildUserSignUpServiceLive

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = "123ABC")

        val serviceError = userSignUpService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.OtpVerifyError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.OtpVerifyError] shouldBe ServiceError.BadRequestError
          .OtpVerifyError(s"Wrong OTP provided for otpID: [${userOtpRow.otpID}]")
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val userSignUpConfig = UserSignUpConfig(
      otpEmailVerificationExpiresAtOffset = 10.seconds,
      otpEmailVerificationResendCooldown = 5.seconds,
      sendEmailVerificationEmailMaxRetries = 3,
      sendEmailVerificationEmailRetryDelay = 1.millisecond,
    )

    val userDetailsRepositoryMock = mock[UserDetailsRepository]
    val userTokenRepositoryMock   = mock[UserTokenRepository]
    val userOtpRepositoryMock     = mock[UserOtpRepository]
    val jwtServiceMock            = mock[JwtService]
    val emailClientMock           = mock[EmailClient]
    val idGeneratorMock           = mock[IDGenerator]
    val otpGeneratorMock          = mock[OtpGenerator]
    val timeProviderMock          = mock[TimeProvider]

    def buildUserSignUpServiceLive: smithy.UserSignUpService[ServiceTask] =
      ZIO
        .service[smithy.UserSignUpService[ServiceTask]]
        .provide(
          UserSignUpService.local,
          ZLayer.succeed(userSignUpConfig),
          EmailDomainValidator.live,
          SignUpEmailPostRequestServiceValidator.live,
          SignUpVerifyEmailPostRequestServiceValidator.live,
          ZLayer.succeed(userDetailsRepositoryMock),
          ZLayer.succeed(userTokenRepositoryMock),
          ZLayer.succeed(userOtpRepositoryMock),
          ZLayer.succeed(jwtServiceMock),
          ZLayer.succeed(emailClientMock),
          ZLayer.succeed(idGeneratorMock),
          ZLayer.succeed(otpGeneratorMock),
          ZLayer.succeed(timeProviderMock),
        )
        .zioValue
  }
}
