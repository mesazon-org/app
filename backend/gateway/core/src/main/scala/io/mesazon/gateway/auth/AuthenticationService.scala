package io.mesazon.gateway.auth

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.repository.{UserCredentialsRepository, UserDetailsRepository}
import io.mesazon.gateway.service.ServiceTask
import org.http4s.*
import org.http4s.headers.Authorization
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
  ) extends AuthenticationService[ServiceTask] {

    override def auth(request: Request[Task]): ServiceTask[Unit] =
      for {
        _ <- ZIO.logDebug("AuthenticationService: auth called")
        basicCredentialsRequest = request.headers
          .get[`Authorization`]
          .collect { case Authorization(BasicCredentials(email, password)) => BasicCredentialsRequest(email, password) }
        basicAuthToken <- ZIO
          .getOrFailWith(ServiceError.UnauthorizedError.TokenMissing)(maybeBasicAuth)
      } yield ???
//      userDetailsRepository.getUserDetailsByEmail(email).someOrFail(ServiceError.UnauthorizedError.).getUserCredentials()
//        .getUserAuthenticationByEmail(email)
//        .flatMap {
//          case Some(userAuthRow) if userAuthRow.password == password =>
//            ZIO.unit
//          case _ =>
//            ZIO.fail(ServiceError.UnauthorizedError.InvalidCredentials)
//        }
//    }
  }

  def observed(service: AuthenticationService[ServiceTask]): AuthenticationService[Task] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val live: ULayer[AuthenticationService] = ZLayer.derive[AuthenticationServiceImpl]
}
