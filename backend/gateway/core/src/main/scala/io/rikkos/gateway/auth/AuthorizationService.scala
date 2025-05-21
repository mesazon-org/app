package io.rikkos.gateway.auth

import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.*
import io.rikkos.gateway.HttpErrorHandler
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

trait AuthorizationService {
  def auth(request: Request[Task]): Task[Unit]
}

object AuthorizationService {

  final private class AuthorizationServiceImpl(state: AuthorizationState) extends AuthorizationService {
    override def auth(request: Request[Task]): Task[Unit] =
      (for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        _          <- ZIO.logDebug(s"Bearer token: [$maybeBearerToken]")
        _          <- ZIO.fromOption(maybeBearerToken).mapError(_ => UnauthorizedError.TokenMissing)
        authMember <- ZIO.succeed(AuthMember(MemberID.assume("test"), Email.assume("eliot.martel@gmail.com")))
        _          <- state.set(authMember)
      } yield ())
        .tapError((authError: UnauthorizedError) =>
          ZIO.logErrorCause("Failed to authorize request", Cause.fail(authError))
        )
  }

  private def observed(service: AuthorizationService): AuthorizationService =
    (request: Request[Task]) =>
      HttpErrorHandler
        .errorResponseHandler(service.auth(request))
        .tapError(error => ZIO.logError(s"Authorization failed: $error"))

  val live = ZLayer {
    for {
      state <- ZIO.service[AuthorizationState]
    } yield observed(new AuthorizationServiceImpl(state): AuthorizationService)
  }
}
