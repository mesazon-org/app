package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.UserOnboardConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.UserOnboardService
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.*
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

        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserCredentialsRepository()
        checkEmailClient()
        checkPasswordService()
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
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserCredentialsRepositoryMock,
        EmailClientMock,
        PasswordServiceMock {

    val userOnboardConfig = UserOnboardConfig(
      sendWelcomeEmailMaxRetries = 3,
      sendWelcomeEmailRetryDelay = 1.millisecond,
    )

    def buildUserOnboardServiceLive(
        authedUser: AuthedUser,
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userCredentialsRows: Map[UserID, UserCredentialsRow] = Map.empty,
        passwordServiceServiceErrorOpt: Option[ServiceError] = None,
        emailClientServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userCredentialsRepositoryServiceErrorOpt: Option[ServiceError] = None,
    ): smithy.UserOnboardService[Task] =
      ZIO
        .service[smithy.UserOnboardService[Task]]
        .provide(
          UserOnboardService.live,
          OnboardPasswordValidator.onboardPasswordValidatorLive,
          passwordServiceMockLive(
            maybeServiceError = passwordServiceServiceErrorOpt
          ),
          Mocks.authorizationStateLive(authedUser),
          userDetailsRepositoryMockLive(
            userDetailsRows = userDetailsRows,
            maybeServiceError = userDetailsRepositoryServiceErrorOpt,
          ),
          userCredentialsRepositoryMockLive(
            userCredentialsRows = userCredentialsRows,
            maybeServiceError = userCredentialsRepositoryServiceErrorOpt,
          ),
          emailClientMockLive(
            maybeServiceError = emailClientServiceErrorOpt
          ),
          ZLayer.succeed(userOnboardConfig),
        )
        .zioValue
  }
}
