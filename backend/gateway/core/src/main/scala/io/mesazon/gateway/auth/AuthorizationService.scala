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

  private final class AuthorizationServiceImpl(
      authorizationState: AuthorizationState,
      jwtService: JwtService,
  ) extends AuthorizationService[ServiceError] {
    override def auth(request: Request[Task]): IO[ServiceError, Unit] =
      for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        jwt <- ZIO
          .getOrFailWith(ServiceError.UnauthorizedError.TokenMissing)(maybeBearerToken)
          .flatMap(jwtRaw =>
            ZIO
              .fromEither(Jwt.either(jwtRaw))
              .mapError(error => ServiceError.UnauthorizedError.FailedToVerifyJwt(s"Failed to apply Jwt: $error"))
          )
        authedUserAccess <- jwtService.verifyAccessToken(jwt)
        _                <- authorizationState.set(AuthedUser(authedUserAccess))
      } yield ()
  }

  private def observed(service: AuthorizationService[ServiceError]): AuthorizationService[Throwable] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val live = ZLayer.derive[AuthorizationServiceImpl] >>> ZLayer.fromFunction(observed)
}
