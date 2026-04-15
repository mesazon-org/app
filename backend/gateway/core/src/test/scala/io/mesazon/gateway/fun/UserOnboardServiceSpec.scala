package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.OtpGenerator
import io.mesazon.gateway.config.{PhoneNumberValidatorConfig, UserOnboardConfig}
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.UserOnboardService
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.PhoneNumberDomainValidator
import io.mesazon.gateway.validation.service.{OnboardDetailsServiceValidator, OnboardPasswordServiceValidator}
import io.mesazon.gateway.{smithy, Mocks}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

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

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest]

        val onboardPasswordResponse =
          userOnboardService.onboardPassword(onboardPasswordRequest).zioValue

        onboardPasswordResponse.onboardStage.value shouldBe "PASSWORD_PROVIDED"

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

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest]

        val onboardPasswordResponse =
          userOnboardService.onboardPassword(onboardPasswordRequest).zioValue

        onboardPasswordResponse.onboardStage.value shouldBe "PASSWORD_PROVIDED"

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

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest]

        val smithyError = userOnboardService.onboardPassword(onboardPasswordRequest).zioError

        smithyError shouldBe a[smithy.Unauthorized]
        smithyError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
      }

      "fail with ValidationError when onboard password with invalid password" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = OnboardStage.EmailVerified)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest]
          .copy(password = "short")

        val smithyError = userOnboardService.onboardPassword(onboardPasswordRequest).zioError

        smithyError shouldBe a[smithy.ValidationError]
        smithyError
          .asInstanceOf[smithy.ValidationError] shouldBe smithy.ValidationError(fields = List("password"))

        checkUserDetailsRepository()
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
        checkTwilioClient()
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

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest]

        val smithyError = userOnboardService.onboardPassword(onboardPasswordRequest).zioError

        smithyError shouldBe a[smithy.InternalServerError]
        smithyError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService(
          expectedHashPasswordCalls = 1
        )
        checkTwilioClient()
      }
    }

    "onboardDetails" should {
      "successfully onboard details for user in password provided stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = OnboardStage.PasswordProvided)

        val userOnboardService = buildUserOnboardServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest]

        val onboardPasswordResponse =
          userOnboardService.onboardDetails(onboardDetailsRequest).zioValue

        onboardPasswordResponse.onboardStage.value shouldBe "PASSWORD_PROVIDED"

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
      sendPhoneVerificationOtpMaxRetries = 3,
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
    ): smithy.UserOnboardService[Task] =
      ZIO
        .service[smithy.UserOnboardService[Task]]
        .provide(
          UserOnboardService.live,
          OtpGenerator.live,
          TimeProvider.liveSystemUTC,
          PhoneNumberUtil.live,
          OnboardPasswordServiceValidator.live,
          OnboardDetailsServiceValidator.live,
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
            maybeServiceError = passwordServiceServiceErrorOpt
          ),
          Mocks.authorizationStateLive(authedUser),
          userDetailsRepositoryMockLive(
            userDetailsRows = userDetailsRows,
            maybeServiceError = userDetailsRepositoryServiceErrorOpt,
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
