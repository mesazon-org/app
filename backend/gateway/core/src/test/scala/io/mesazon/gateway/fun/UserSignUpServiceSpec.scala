package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.UserSignUpConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{ServiceTask, UserSignUpService}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.{
  SignUpEmailPostRequestServiceValidator,
  SignUpVerifyEmailPostRequestServiceValidator,
}
import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.Instant

class UserSignUpServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries {

  "UserSignUpService" when {
    "signUpEmail" should {
      "successfully sign up a new user with valid email" in new TestContext {

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map.empty,
          userOtpRows = Map.empty,
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

        val signUpEmailPostResponse = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.otpID shouldBe OtpID.assume("otp-id").value
        signUpEmailPostResponse.otpExpiresInSeconds shouldBe userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1,
          expectedInsertUserDetailsCalls = 1,
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedUpsertUserOtpCalls = 1
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
        checkJwtService()
      }

      "successfully re-sign up a user with sign up email stages with otp found and not expired" in new TestContext {
        val userID                   = arbitrarySample[UserID]
        val email                    = arbitrarySample[Email]
        val onboardStagesSignupEmail = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userOtpRow               = arbitrarySample[UserOtpRow]
          .copy(userID = userID, otpType = OtpType.EmailVerification)
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = onboardStagesSignupEmail, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = email.value)

        val signUpEmailPostResponse = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.otpID shouldBe OtpID.assume("otp-id").value
        signUpEmailPostResponse.otpExpiresInSeconds shouldBe userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedUpsertUserOtpCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
        checkJwtService()
      }

      "successfully re-sign up a user with sign up email when no otp found" in new TestContext {
        val userID                   = arbitrarySample[UserID]
        val email                    = arbitrarySample[Email]
        val onboardStagesSignupEmail = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow           = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = onboardStagesSignupEmail, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map.empty,
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = email.value)

        val signUpEmailPostResponse = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.otpID shouldBe OtpID.assume("otp-id").value
        signUpEmailPostResponse.otpExpiresInSeconds shouldBe userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedUpsertUserOtpCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
        checkJwtService()
      }

      "successfully re-sign up a user with sign up email when otp found but expired" in new TestContext {
        val userID                   = arbitrarySample[UserID]
        val email                    = arbitrarySample[Email]
        val onboardStagesSignupEmail = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userOtpRow               = arbitrarySample[UserOtpRow]
          .copy(
            userID = userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(Instant.now.minusSeconds(10)),
          )
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = onboardStagesSignupEmail, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = email.value)

        val signUpEmailPostResponse = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.otpID shouldBe OtpID.assume("otp-id").value
        signUpEmailPostResponse.otpExpiresInSeconds shouldBe userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedUpsertUserOtpCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = 1
        )
        checkJwtService()
      }

      "successfully fake sign up a user when onboard stage is not in sign up email stages" in new TestContext {
        val userID                     = arbitrarySample[UserID]
        val email                      = arbitrarySample[Email]
        val onboardStageNonSingupEmail =
          Random.shuffle(OnboardStage.values.diff(OnboardStage.signUpEmailStages).toList).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = onboardStageNonSingupEmail, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow)
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = email.value)

        val signUpEmailPostResponse = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.otpID should not be OtpID.assume("otp-id").value
        signUpEmailPostResponse.otpExpiresInSeconds shouldBe userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByUserIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }

      "fail with ValidationError when sign up a user with invalid email" in new TestContext {
        val userSignupService = buildUserSignupServiceLive()

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = "invalid-email")

        val serviceError = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioError

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

        checkUserDetailsRepository()
        checkUserTokenRepository()
        checkUserOtpRepository()
        checkEmailClient()
        checkJwtService()
      }

      "fail and retry sign up for already existing user when sending email when email client fails" in new TestContext {
        val userID                   = arbitrarySample[UserID]
        val email                    = arbitrarySample[Email]
        val onboardStagesSignupEmail = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
        val userDetailsRow           = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = onboardStagesSignupEmail, email = email)
        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          emailClientServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("Email client error")),
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
          .copy(email = email.value)

        val serviceError = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Email client error")

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedUpsertUserOtpCalls = 1,
          expectedGetUserOtpByUserIDCalls = 1,
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = userSignUpConfig.sendEmailVerificationEmailMaxRetries + 1
        )
        checkJwtService()
      }

      "fail and retry sign up new user when sending email when email client fails" in new TestContext {
        val userSignupService = buildUserSignupServiceLive(
          emailClientServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("Email client error"))
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

        val serviceError = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Email client error")

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1,
          expectedInsertUserDetailsCalls = 1,
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedUpsertUserOtpCalls = 1
        )
        checkEmailClient(
          expectedSendEmailVerificationEmailCalls = userSignUpConfig.sendEmailVerificationEmailMaxRetries + 1
        )
        checkJwtService()
      }

      "fail with InternalServerError when userDetailsRepository fails to get user details by email" in new TestContext {
        val userSignupService = buildUserSignupServiceLive(
          userDetailsRepositoryServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("Database error"))
        )

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

        val serviceError = userSignupService.signUpEmailPost(signUpEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Database error")

        checkUserDetailsRepository(
          expectedGetUserDetailsByEmailCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository()
        checkEmailClient()
        checkJwtService()
      }
    }

    "signUpVerifyEmail" should {
      "successfully verify email with valid otp" in new TestContext {
        val userID     = arbitrarySample[UserID]
        val email      = arbitrarySample[Email]
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(Instant.now.plusSeconds(10)),
          )
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = OnboardStage.EmailVerification, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val signUpVerifyEmailPostResponse =
          userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse.onboardStage.name shouldBe "EMAIL_VERIFIED"

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1,
          expectedUpdateUserDetailsCalls = 1,
        )
        checkUserTokenRepository(
          expectedUpsertUserTokenCalls = 1,
          expectedDeleteAllUserTokensCalls = 1,
        )
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1,
          expectedDeleteUserOtpCalls = 1,
        )
        checkEmailClient()
        checkJwtService(
          expectedGenerateAccessTokenCalls = 1,
          expectedGenerateRefreshTokenCalls = 1,
        )
      }

      "fail with BadRequest when verify email with invalid otp format" in new TestContext {
        val userID     = arbitrarySample[UserID]
        val email      = arbitrarySample[Email]
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(Instant.now.plusSeconds(10)),
          )
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = OnboardStage.EmailVerification, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = "invalid-otp")

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

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

        checkUserDetailsRepository()
        checkUserTokenRepository()
        checkUserOtpRepository()
        checkEmailClient()
        checkJwtService()
      }

      "fail with Unauthorized when verify email with non-existing otp" in new TestContext {
        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = OtpID.assume("non-existing-otp-id").value)

        val userSignupService = buildUserSignupServiceLive()

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"No otp found for otpID: [${signUpVerifyEmailPostRequest.otpID}] and otpType: [${OtpType.EmailVerification}]"
          )

        checkUserDetailsRepository()
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }

      "fail with Unauthorized when verify email send for otp type non email verification" in new TestContext {
        val otpTypeNonEmailVerification =
          Random.shuffle(OtpType.values.diff(List(OtpType.EmailVerification)).toList).zioValue.head
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(otpType = otpTypeNonEmailVerification)

        val userSignupService = buildUserSignupServiceLive(
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow)
        )

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(
            s"No otp found for otpID: [${userOtpRow.otpID}] and otpType: [${OtpType.EmailVerification}]"
          )

        checkUserDetailsRepository()
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }

      "fail with Unauthorized when verify email is send for user with no email verification stage" in new TestContext {
        val userID     = arbitrarySample[UserID]
        val email      = arbitrarySample[Email]
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(userID = userID, otpType = OtpType.EmailVerification)
        val onboardStageNonEmailVerification =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.signUpVerifyEmailStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = onboardStageNonEmailVerification, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe ServiceError.UnauthorizedError
          .FailedOnboardStage(
            onboardStageUser = onboardStageNonEmailVerification,
            onboardStagesAllowed = OnboardStage.signUpVerifyEmailStages,
          )

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }

      "fail with Unauthorized when verify email with expired otp" in new TestContext {
        val userID     = arbitrarySample[UserID]
        val email      = arbitrarySample[Email]
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(Instant.now.minusSeconds(10)),
          )
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = OnboardStage.EmailVerification, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(s"Wrong or expired OTP provided for otpID: [${userOtpRow.otpID}]")

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }

      "fail with Unauthorized when verify email with wrong otp" in new TestContext {
        val userID     = arbitrarySample[UserID]
        val email      = arbitrarySample[Email]
        val userOtpRow = arbitrarySample[UserOtpRow]
          .copy(
            userID = userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(Instant.now.plusSeconds(10)),
          )
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = userID, onboardStage = OnboardStage.EmailVerification, email = email)

        val userSignupService = buildUserSignupServiceLive(
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userOtpRows = Map(userOtpRow.otpID -> userOtpRow),
        )

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
          .copy(otpID = userOtpRow.otpID.value, otp = "123ABC")

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.OtpValidationError]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.OtpValidationError] shouldBe ServiceError.UnauthorizedError
          .OtpValidationError(s"Wrong or expired OTP provided for otpID: [${userOtpRow.otpID}]")

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }

      "fail with InternalServerError when userOtpRepository fails to get user otp" in new TestContext {
        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]

        val userSignupService = buildUserSignupServiceLive(
          userOtpRepositoryServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("Database error"))
        )

        val serviceError = userSignupService.signUpVerifyEmailPost(signUpVerifyEmailPostRequest).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError("Database error")

        checkUserDetailsRepository()
        checkUserTokenRepository()
        checkUserOtpRepository(
          expectedGetUserOtpByOtpIDCalls = 1
        )
        checkEmailClient()
        checkJwtService()
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserTokenRepositoryMock,
        UserOtpRepositoryMock,
        JwtServiceMock,
        EmailClientMock {

    val userSignUpConfig = UserSignUpConfig(
      otpEmailVerificationExpiresAtOffset = 10.seconds,
      otpEmailVerificationResendCooldown = 5.seconds,
      sendEmailVerificationEmailMaxRetries = 3,
      sendEmailVerificationEmailRetryDelay = 1.millisecond,
    )

    def buildUserSignupServiceLive(
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userTokenRows: Map[TokenID, UserTokenRow] = Map.empty,
        userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
        jwtServiceServiceErrorOpt: Option[ServiceError] = None,
        emailClientServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userTokenRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userOtpRepositoryServiceErrorOpt: Option[ServiceError] = None,
    ): smithy.UserSignUpService[ServiceTask] =
      ZIO
        .service[smithy.UserSignUpService[ServiceTask]]
        .provide(
          UserSignUpService.local,
          ZLayer.succeed(userSignUpConfig),
          EmailDomainValidator.live,
          userDetailsRepositoryMockLive(
            userDetailsRows = userDetailsRows,
            serviceErrorOpt = userDetailsRepositoryServiceErrorOpt,
          ),
          userTokenRepositoryMockLive(
            userTokenRows = userTokenRows,
            maybeServiceError = userTokenRepositoryServiceErrorOpt,
          ),
          userOtpRepositoryMockLive(
            userOtpRows = userOtpRows,
            serviceErrorOpt = userOtpRepositoryServiceErrorOpt,
          ),
          jwtServiceMockLive(
            maybeServiceError = jwtServiceServiceErrorOpt
          ),
          emailClientMockLive(
            maybeServiceError = emailClientServiceErrorOpt
          ),
          IDGenerator.uuidGeneratorLive,
          OtpGenerator.live,
          TimeProvider.liveSystemUTC,
          SignUpEmailPostRequestServiceValidator.live,
          SignUpVerifyEmailPostRequestServiceValidator.live,
        )
        .zioValue
  }
}
