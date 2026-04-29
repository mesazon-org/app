package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.repository.{UserCredentialsRepository, UserDetailsRepository}
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.validation.service.BasicCredentialsServiceValidator
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials as Http4sBasicCredentials, *}
import zio.*

trait AuthenticationService[F[_]] {
  def auth(request: Request[Task]): F[Unit]
}

object AuthenticationService {

  case class BasicCredentialsRequest(email: String, password: String)

  private final class AuthenticationServiceImpl(
      userDetailsRepository: UserDetailsRepository,
      userCredentialsRepository: UserCredentialsRepository,
      passwordService: PasswordService,
      authState: AuthState,
      basicCredentialsServiceValidator: BasicCredentialsServiceValidator,
  ) extends AuthenticationService[ServiceTask] {

    override def auth(request: Request[Task]): ServiceTask[Unit] =
      for {
        _ <- ZIO.logDebug("AuthenticationService: auth called")
        basicCredentialsRequestOpt = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Http4sBasicCredentials(email, password)) =>
            BasicCredentialsRequest(email, password)
          }
        basicCredentialsRequest <- ZIO.getOrFailWith(ServiceError.UnauthorizedError.BasicCredentialsMissing)(
          basicCredentialsRequestOpt
        )
        basicCredentials <- basicCredentialsServiceValidator.validate(basicCredentialsRequest)
        userDetails      <- userDetailsRepository
          .getUserDetailsByEmail(basicCredentials.email)
          .someOrFail(
            ServiceError.UnauthorizedError.EmailNotFound
          )
        userCredentials <- userCredentialsRepository
          .getUserCredentials(userDetails.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"User credentials not found for userID: [${userDetails.userID}], could only occur if user details exist but credentials do not"
            )
          )
        isPasswordVerified <- passwordService.verifyPassword(basicCredentials.password, userCredentials.passwordHash)
        _                  <-
          if (isPasswordVerified) ZIO.unit
          else ZIO.fail(ServiceError.UnauthorizedError.InvalidCredentials)
        _ <- authState.set(AuthedUser(userDetails.userID))
      } yield ()
  }

  def observed(service: AuthenticationService[ServiceTask]): AuthenticationService[Task] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val live = ZLayer.derive[AuthenticationServiceImpl] >>> ZLayer.fromFunction(observed)
}
