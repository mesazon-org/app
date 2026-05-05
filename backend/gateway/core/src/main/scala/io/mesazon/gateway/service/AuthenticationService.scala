package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.{UserActionAttemptRepository, UserCredentialsRepository, UserDetailsRepository}
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.validation.service.BasicCredentialsRequestServiceValidator
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
      basicCredentialsRequestServiceValidator: BasicCredentialsRequestServiceValidator,
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
        basicCredentialsRequest <- ZIO.getOrFailWith(ServiceError.BadRequestError.BasicCredentialsMissing)(
          basicCredentialsRequestOpt
        )
        basicCredentials <- basicCredentialsRequestServiceValidator.validate(basicCredentialsRequest)
        userDetails      <- userDetailsRepository
          .getUserDetailsByEmail(basicCredentials.email)
          .someOrFail(
            ServiceError.UnauthorizedError.EmailNotFound
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetails.onboardStage,
          onboardStagesAllowed = OnboardStage.signInAllowedStages,
        )
        userActionAttemptRow <- userActionAttemptRepository
          .getAndIncreaseUserActionAttempt(
            userDetails.userID,
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
              ServiceError.UnauthorizedError.TooManySignInAttempts(
                userDetails.userID,
                ActionAttemptType.SignIn,
                authenticationConfig.signInAttemptsBlockDuration.toSeconds,
              )
            )
          else ZIO.unit
        userCredentials <- userCredentialsRepository
          .getUserCredentials(userDetails.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"User credentials not found for userID: [${userDetails.userID}], could only occur if user details exist but credentials do not"
            )
          )
        isPasswordVerified <- passwordService.verifyPassword(basicCredentials.password, userCredentials.passwordHash)
        _                  <-
          if (isPasswordVerified)
            userActionAttemptRepository.deleteUserActionAttempt(userDetails.userID, ActionAttemptType.SignIn)
          else ZIO.fail(ServiceError.UnauthorizedError.InvalidCredentials)
        _ <- authState.set(AuthedUser(userDetails.userID))
      } yield ()
  }

  def observed(service: AuthenticationService[ServiceTask]): AuthenticationService[Task] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val local = ZLayer.derive[AuthenticationServiceImpl].project[AuthenticationService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
