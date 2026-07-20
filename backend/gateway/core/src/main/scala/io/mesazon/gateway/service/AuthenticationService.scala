package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.{UserActionAttemptRepository, UserCredentialsRepository, UserDetailsRepository}
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.validation.service.UserSignInRequestValidator
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials as Http4sBasicCredentials, *}
import zio.*

trait AuthenticationService[F[_]] {
  def auth(request: Request[Task]): F[Unit]
}

object AuthenticationService {

  case class BasicCredentialsRequest(email: String, password: String)

  private final class AuthenticationServiceImpl(
      authenticationConfig: AuthenticationConfig,
      userDetailsRepository: UserDetailsRepository,
      userCredentialsRepository: UserCredentialsRepository,
      userActionAttemptRepository: UserActionAttemptRepository,
      passwordService: PasswordService,
      authState: AuthState,
      userSignInRequestValidator: UserSignInRequestValidator,
      timeProvider: TimeProvider,
  ) extends AuthenticationService[ServiceTask] {

    override def auth(request: Request[Task]): ServiceTask[Unit] =
      for {
        _ <- ZIO.logDebug("AuthenticationService: auth called")
        basicCredentialsRequestOpt = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Http4sBasicCredentials(email, password)) =>
            BasicCredentialsRequest(email, password)
          }
        basicCredentialsRequest <- ZIO.getOrFailWith(
          ServiceError.UnauthorizedError.AuthHeaderMissingError(s"${Authorization.name}: ${AuthScheme.Basic}")
        )(
          basicCredentialsRequestOpt
        )
        basicCredentials <- userSignInRequestValidator.validatedBasicCredentialsRequest(basicCredentialsRequest)
        userDetailsRow   <- userDetailsRepository
          .getUserDetailsByEmail(basicCredentials.email)
          .someOrFail(
            ServiceError.UnauthorizedError.AuthenticationEmailNotFound
          )
        _ <- verifyOnboardStage(
          userID = userDetailsRow.userID,
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.signInAllowedStages,
        )
        userActionAttemptRow <- userActionAttemptRepository
          .getAndIncreaseUserActionAttempt(
            userDetailsRow.userID,
            ActionAttemptType.SignIn,
          )
        instantNow <- timeProvider.instantNow
        _          <-
          if (
            userActionAttemptRow.attempts.value > authenticationConfig.signInAttemptsMax &&
            userActionAttemptRow.updatedAt.value
              .plusSeconds(authenticationConfig.signInAttemptsBlockDuration.toSeconds)
              .isAfter(instantNow)
          )
            ZIO.fail(
              ServiceError.UnauthorizedError.AuthenticationTooManySignInAttempts(
                userDetailsRow.userID,
                ActionAttemptType.SignIn,
                authenticationConfig.signInAttemptsBlockDuration.toSeconds,
              )
            )
          else ZIO.unit
        userCredentialsRow <- userCredentialsRepository
          .getUserCredentials(userDetailsRow.userID)
          .someOrFail(
            ServiceError.InternalServerError.AuthenticationError(
              s"User credentials not found for userID: [${userDetailsRow.userID}], could only occur if user details exist but credentials do not"
            )
          )
        isPasswordVerified <- passwordService.verifyPassword(basicCredentials.password, userCredentialsRow.passwordHash)
        _                  <-
          if (isPasswordVerified)
            userActionAttemptRepository.deleteUserActionAttempt(userDetailsRow.userID, ActionAttemptType.SignIn)
          else ZIO.fail(ServiceError.UnauthorizedError.AuthenticationInvalidCredentials)
        _ <- authState.set(AuthedUser(userDetailsRow.userID))
      } yield ()
  }

  def observed(service: AuthenticationService[ServiceTask]): AuthenticationService[Task] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val local = ZLayer.derive[AuthenticationServiceImpl].project[AuthenticationService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
