package io.mesazon.gateway.fun

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.gateway.{ActionAttemptType, *}
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.{UserActionAttemptRepository, UserCredentialsRepository, UserDetailsRepository}
import io.mesazon.gateway.service.*
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.BasicCredentialsRequestServiceValidator
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
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID)

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(1),
          )

        val password = arbitrarySample[Password]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userCredentialsRepositoryMock.getUserCredentials
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userCredentialsRow))
            .once(),
          passwordServiceMock.verifyPassword
            .expects(password, userCredentialsRow.passwordHash)
            .returningZIO(true)
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIOUnit,
          authStateMock.set.expects(AuthedUser(userDetailsRow.userID)).returningZIOUnit,
        )

        val authenticationService = buildAuthenticationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)
      }

      "successfully authenticate user with sign in attempts under max and block duration passed" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val passwordHash       = arbitrarySample[PasswordHash]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = passwordHash)

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

        val password = arbitrarySample[Password]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userCredentialsRepositoryMock.getUserCredentials
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userCredentialsRow))
            .once(),
          passwordServiceMock.verifyPassword
            .expects(password, userCredentialsRow.passwordHash)
            .returningZIO(true)
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIOUnit,
          authStateMock.set.expects(AuthedUser(userDetailsRow.userID)).returningZIOUnit,
        )

        val authenticationService = buildAuthenticationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)
      }

      "successfully authenticate user with sign in attempts at max but block duration passed" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val passwordHash       = arbitrarySample[PasswordHash]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = passwordHash)

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

        val password = arbitrarySample[Password]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userCredentialsRepositoryMock.getUserCredentials
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userCredentialsRow))
            .once(),
          passwordServiceMock.verifyPassword
            .expects(password, userCredentialsRow.passwordHash)
            .returningZIO(true)
            .once(),
          userActionAttemptRepositoryMock.deleteUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIOUnit,
          authStateMock.set.expects(AuthedUser(userDetailsRow.userID)).returningZIOUnit,
        )

        val authenticationService = buildAuthenticationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)
      }

      "fail with AuthenticationCredentialsMissing to authenticate user with missing basic credentials" in new TestContext {
        val authenticationService = buildAuthenticationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.BadRequestError.AuthenticationCredentialsMissing.type]
        serviceError
          .asInstanceOf[
            ServiceError.BadRequestError.AuthenticationCredentialsMissing.type
          ] shouldBe ServiceError.BadRequestError.AuthenticationCredentialsMissing
      }

      "fail with ValidationError to authenticate user with invalid basic credentials" in new TestContext {
        val authenticationService = buildAuthenticationService

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
      }

      "fail with FailedOnboardStage to authenticate user with no allowed sing in onboardStage" in new TestContext {
        val onboardStage =
          Random.shuffle(OnboardStage.values.toList.diff(OnboardStage.signInAllowedStages)).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once()
        )

        val authenticationService = buildAuthenticationService

        val password = arbitrarySample[Password]

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

      }

      "fail with AuthenticationInvalidCredentials to authenticate user with invalid credentials" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val password           = arbitrarySample[Password]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID)

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(1),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userCredentialsRepositoryMock.getUserCredentials
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userCredentialsRow))
            .once(),
          passwordServiceMock.verifyPassword
            .expects(password, userCredentialsRow.passwordHash)
            .returningZIO(false)
            .once(),
        )

        val authenticationService = buildAuthenticationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.AuthenticationInvalidCredentials.type]
        serviceError
          .asInstanceOf[
            ServiceError.UnauthorizedError.AuthenticationInvalidCredentials.type
          ] shouldBe ServiceError.UnauthorizedError.AuthenticationInvalidCredentials
      }

      "fail with AuthenticationTooManySignInAttempts to authenticate user with too many sign in attempts and is in blocked duration" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(authenticationConfig.signInAttemptsMax + 1),
            updatedAt = UpdatedAt(
              instantNow.minusSeconds(authenticationConfig.signInAttemptsBlockDuration.toSeconds - 1)
            ),
          )

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
        )

        val authenticationService = buildAuthenticationService

        val password = arbitrarySample[Password]

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.AuthenticationTooManySignInAttempts]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.AuthenticationTooManySignInAttempts] shouldBe
          ServiceError.UnauthorizedError.AuthenticationTooManySignInAttempts(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            blockDurationSeconds = authenticationConfig.signInAttemptsBlockDuration.toSeconds,
          )
      }

      "fail with AuthenticationError to authenticate user when user credentials are not found for existing user details" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(1),
          )

        val password = arbitrarySample[Password]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(userDetailsRow.email)
            .returningZIO(Some(userDetailsRow))
            .once(),
          userActionAttemptRepositoryMock.getAndIncreaseUserActionAttempt
            .expects(userDetailsRow.userID, ActionAttemptType.SignIn)
            .returningZIO(userActionAttemptRow)
            .once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          userCredentialsRepositoryMock.getUserCredentials
            .expects(userDetailsRow.userID)
            .returningZIO(None) // No user credentials found for existing user details
            .once(),
        )

        val authenticationService = buildAuthenticationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val serviceError = authenticationService.auth(request).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.AuthenticationError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.AuthenticationError] shouldBe ServiceError.InternalServerError
          .AuthenticationError(
            s"User credentials not found for userID: [${userDetailsRow.userID}], could only occur if user details exist but credentials do not"
          )
      }

      "fail with UnexpectedError to authenticate user when user details repository fails" in new TestContext {
        val basicCredentials = arbitrarySample[BasicCredentials]

        inSequence(
          userDetailsRepositoryMock.getUserDetailsByEmail
            .expects(basicCredentials.email)
            .failingZIO(ServiceError.InternalServerError.UnexpectedError("Database connection error"))
            .once()
        )

        val authenticationService = buildAuthenticationService

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
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    val authenticationConfig = AuthenticationConfig(
      signInAttemptsMax = 5,
      signInAttemptsBlockDuration = Duration.fromSeconds(2),
    )

    val userDetailsRepositoryMock       = mock[UserDetailsRepository]
    val authStateMock                   = mock[AuthState]
    val userActionAttemptRepositoryMock = mock[UserActionAttemptRepository]
    val timeProviderMock                = mock[TimeProvider]
    val passwordServiceMock             = mock[PasswordService]
    val userCredentialsRepositoryMock   = mock[UserCredentialsRepository]

    def buildAuthenticationService: AuthenticationService[ServiceTask] = ZIO
      .service[AuthenticationService[ServiceTask]]
      .provide(
        AuthenticationService.local,
        EmailDomainValidator.live,
        BasicCredentialsRequestServiceValidator.live,
        ZLayer.succeed(authenticationConfig),
        ZLayer.succeed(authStateMock),
        ZLayer.succeed(userActionAttemptRepositoryMock),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(passwordServiceMock),
        ZLayer.succeed(userDetailsRepositoryMock),
        ZLayer.succeed(userCredentialsRepositoryMock),
      )
      .zioValue
  }
}
