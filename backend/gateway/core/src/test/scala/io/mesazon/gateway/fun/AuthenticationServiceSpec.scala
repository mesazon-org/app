package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.BasicCredentialsServiceValidator
import io.mesazon.testkit.base.ZWordSpecBase
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials as Http4sBasicCredentials, *}
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class AuthenticationServiceSpec extends ZWordSpecBase, RepositoryArbitraries {

  "AuthenticationService" when {
    "auth" should {
      "successfully authenticate user" in new TestContext {
        val password     = arbitrarySample[Password]
        val onboardStage = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = PasswordHash.assume(password.value))

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)

        checkAuthState(expectedSetCalls = 1)
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1,
          expectedDeleteUserActionAttemptCalls = 1,
        )
        checkPasswordService(expectedVerifyPasswordCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository(expectedGetUserCredentialsCalls = 1)
      }

      "successfully authenticate user with sign in attempts under max and block duration passed" in new TestContext {
        val password       = arbitrarySample[Password]
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = PasswordHash.assume(password.value))
        val instantNow           = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(authenticationConfig.signInAttemptsMax), // Under max attempts or equals
            updatedAt = UpdatedAt(
              instantNow.minusSeconds(
                authenticationConfig.signInAttemptsBlockDuration.toSeconds + 1
              ) // Block duration passed
            ),
          )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)

        checkAuthState(expectedSetCalls = 1)
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1,
          expectedDeleteUserActionAttemptCalls = 1,
        )
        checkPasswordService(expectedVerifyPasswordCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository(expectedGetUserCredentialsCalls = 1)
      }

      "successfully authenticate user with sign in attempts at max but block duration passed" in new TestContext {
        val password       = arbitrarySample[Password]
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = PasswordHash.assume(password.value))
        val instantNow           = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(authenticationConfig.signInAttemptsMax + 1), // At max attempts
            updatedAt = UpdatedAt(
              instantNow.minusSeconds(
                authenticationConfig.signInAttemptsBlockDuration.toSeconds + 1
              ) // Block duration passed
            ),
          )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)

        checkAuthState(expectedSetCalls = 1)
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1,
          expectedDeleteUserActionAttemptCalls = 1,
        )
        checkPasswordService(expectedVerifyPasswordCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository(expectedGetUserCredentialsCalls = 1)
      }

      "fail with BasicCredentialsMissing to authenticate user with missing basic credentials" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val authenticationService = buildAuthenticationService(
          authedUser = authedUser
        )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.BasicCredentialsMissing.type]
        serviceError
          .asInstanceOf[
            ServiceError.BadRequestError.BasicCredentialsMissing.type
          ] shouldBe ServiceError.BadRequestError.BasicCredentialsMissing

        checkAuthState()
        checkUserActionAttemptRepository()
        checkPasswordService()
        checkUserDetailsRepository()
        checkUserCredentialsRepository()
      }

      "fail with ValidationError to authenticate user with invalid basic credentials" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val authenticationService = buildAuthenticationService(
          authedUser = authedUser
        )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials("invalid_email", "invalid_password")
            )
          )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError] shouldBe ServiceError.BadRequestError
          .ValidationError(invalidFields =
            Seq(
              InvalidFieldError("email", "Invalid email format: [invalid_email], error: [null]", "invalid_email"),
              InvalidFieldError(
                "password",
                "Should match ^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$",
                "invalid_password",
              ),
            )
          )

        checkAuthState()
        checkUserActionAttemptRepository()
        checkPasswordService()
        checkUserDetailsRepository()
        checkUserCredentialsRepository()
      }

      "fail with FailedOnboardStage to authenticate user with no allowed sing in onboardStage" in new TestContext {
        val password     = arbitrarySample[Password]
        val onboardStage =
          Random.shuffle(OnboardStage.values.toList.diff(OnboardStage.signInAllowedStages)).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = PasswordHash.assume(password.value))

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.FailedOnboardStage] shouldBe
          ServiceError.UnauthorizedError.FailedOnboardStage(
            onboardStageUser = onboardStage,
            onboardStagesAllowed = OnboardStage.signInAllowedStages,
          )

        checkAuthState()
        checkUserActionAttemptRepository()
        checkPasswordService()
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository()
      }

      "fail with InvalidCredentials to authenticate user with invalid credentials" in new TestContext {
        val password       = arbitrarySample[Password]
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID)

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.InvalidCredentials.type]
        serviceError
          .asInstanceOf[
            ServiceError.UnauthorizedError.InvalidCredentials.type
          ] shouldBe ServiceError.UnauthorizedError.InvalidCredentials

        checkAuthState()
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1
        )
        checkPasswordService(expectedVerifyPasswordCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository(expectedGetUserCredentialsCalls = 1)
      }

      "fail with TooManySignInAttempts to authenticate user with too many sign in attempts and is in blocked duration" in new TestContext {
        val password       = arbitrarySample[Password]
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val instantNow           = Instant.now.truncatedTo(ChronoUnit.MILLIS)
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(authenticationConfig.signInAttemptsMax + 1),
            updatedAt = UpdatedAt(
              instantNow.minusSeconds(authenticationConfig.signInAttemptsBlockDuration.toSeconds - 1)
            ),
          )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userActionAttemptRowOpt = Some(userActionAttemptRow),
        )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.TooManySignInAttempts]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.TooManySignInAttempts] shouldBe
          ServiceError.UnauthorizedError.TooManySignInAttempts(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            blockDurationSeconds = authenticationConfig.signInAttemptsBlockDuration.toSeconds,
          )

        checkAuthState()
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1
        )
        checkPasswordService()
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository()
      }

      "fail with InternalServerError to authenticate user when user credentials are not found for existing user details" in new TestContext {
        val password       = arbitrarySample[Password]
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map.empty, // No credentials for existing user details
        )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"User credentials not found for userID: [${userDetailsRow.userID}], could only occur if user details exist but credentials do not"
          )

        checkAuthState()
        checkUserActionAttemptRepository(
          expectedGetAndIncreaseUserActionAttemptCalls = 1
        )
        checkPasswordService()
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository(expectedGetUserCredentialsCalls = 1)
      }

      "fail with InternalServerError to authenticate user when user details repository fails" in new TestContext {
        val authedUser       = arbitrarySample[AuthedUser]
        val basicCredentials = arbitrarySample[BasicCredentials]

        val authenticationService = buildAuthenticationService(
          authedUser = authedUser,
          userDetailsRepositoryServiceErrorOpt =
            Some(ServiceError.InternalServerError.UnexpectedError("Database connection error")),
        )

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(basicCredentials.email.value, basicCredentials.password.value)
            )
          )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            "Database connection error"
          )

        checkAuthState()
        checkUserActionAttemptRepository()
        checkPasswordService()
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository()
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserCredentialsRepositoryMock,
        PasswordServiceMock,
        AuthStateMock,
        UserActionAttemptRepositoryMock {

    val authenticationConfig = AuthenticationConfig(
      signInAttemptsMax = 5,
      signInAttemptsBlockDuration = Duration.fromMillis(5),
    )

    def buildAuthenticationService(
        authedUser: AuthedUser,
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userCredentialsRows: Map[UserID, UserCredentialsRow] = Map.empty,
        userActionAttemptRowOpt: Option[UserActionAttemptRow] = None,
        passwordServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userCredentialsRepositoryServiceErrorOpt: Option[ServiceError] = None,
    ): AuthenticationService[ServiceTask] = ZIO
      .service[AuthenticationService[ServiceTask]]
      .provide(
        AuthenticationService.local,
        EmailDomainValidator.live,
        BasicCredentialsServiceValidator.live,
        authStateMockLive(authedUser = authedUser),
        userActionAttemptRepositoryMockLive(
          userActionAttemptRowOpt = userActionAttemptRowOpt
        ),
        TimeProvider.liveSystemUTC,
        passwordServiceMockLive(
          serviceErrorOpt = passwordServiceErrorOpt
        ),
        userDetailsRepositoryMockLive(
          userDetailsRows = userDetailsRows,
          serviceErrorOpt = userDetailsRepositoryServiceErrorOpt,
        ),
        userCredentialsRepositoryMockLive(
          userCredentialsRows = userCredentialsRows,
          serviceErrorOpt = userCredentialsRepositoryServiceErrorOpt,
        ),
        ZLayer.succeed(authenticationConfig),
      )
      .zioValue
  }

}
