package io.rikkos.gateway.auth

import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.*
import io.rikkos.gateway.HttpErrorHandler
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

trait AuthorizationService[E] {
  def auth(request: Request[Task]): IO[E, Unit]
}

object AuthorizationService {

  final private case class AuthorizationServiceImpl(state: AuthorizationState)
      extends AuthorizationService[ServiceError] {
    override def auth(request: Request[Task]): IO[ServiceError, Unit] =
      for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        _          <- ZIO.logDebug(s"Bearer token: [$maybeBearerToken]")
        _          <- ZIO.fromOption(maybeBearerToken).mapError(_ => UnauthorizedError.TokenMissing)
        authedUser <- ZIO.succeed(AuthedUser(UserID.assume("test"), Email.assume("eliot.martel@gmail.com")))
        _          <- state.set(authedUser)
      } yield ()
  }

  private def observed(service: AuthorizationService[ServiceError]): AuthorizationService[Throwable] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val live = ZLayer.fromFunction(AuthorizationServiceImpl.apply) >>> ZLayer.fromFunction(observed)
}
