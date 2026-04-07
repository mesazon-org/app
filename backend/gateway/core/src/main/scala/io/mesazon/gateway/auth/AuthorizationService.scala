package io.mesazon.gateway.auth

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

trait AuthorizationService[E] {
  def auth(request: Request[Task]): IO[E, Unit]
}

object AuthorizationService {

  private final class AuthorizationServiceImpl(state: AuthorizationState, jwtService: JwtService)
      extends AuthorizationService[ServiceError] {
    override def auth(request: Request[Task]): IO[ServiceError, Unit] =
      for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        _   <- ZIO.logDebug(s"Bearer token: [$maybeBearerToken]")
        jwt <- ZIO
          .getOrFailWith(ServiceError.UnauthorizedError.TokenMissing)(maybeBearerToken)
          .flatMap(jwtRaw =>
            ZIO
              .fromEither(Jwt.either(jwtRaw))
              .mapError(error => ServiceError.UnauthorizedError.FailedToVerifyJwt(s"Failed to apply Jwt: $error"))
          )
        _          <- jwtService.verifyAccessToken(jwt)
        authedUser <- ZIO.succeed(AuthedUser(UserID.assume("test"), Email.assume("eliot.martel@gmail.com")))
        _          <- state.set(authedUser)
      } yield ()
  }

  private def observed(service: AuthorizationService[ServiceError]): AuthorizationService[Throwable] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val live = ZLayer.derive[AuthorizationServiceImpl] >>> ZLayer.fromFunction(observed)
}
