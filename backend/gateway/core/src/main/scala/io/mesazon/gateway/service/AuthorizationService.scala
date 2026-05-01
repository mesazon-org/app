package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.state.*
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

trait AuthorizationService[F[_]] {
  def auth(request: Request[Task]): F[Unit]
}

object AuthorizationService {

  private final class AuthorizationServiceImpl(
      authState: AuthState,
      jwtService: JwtService,
  ) extends AuthorizationService[ServiceTask] {

    override def auth(request: Request[Task]): ServiceTask[Unit] =
      for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        accessToken <- ZIO
          .getOrFailWith(ServiceError.UnauthorizedError.TokenMissing)(maybeBearerToken)
          .flatMap(accessTokenRaw =>
            ZIO
              .fromEither(AccessToken.either(accessTokenRaw))
              .mapError(error =>
                ServiceError.UnauthorizedError.FailedToVerifyJwt(s"Failed to apply AccessToken: $error")
              )
          )
        authedUserAccess <- jwtService.verifyAccessToken(accessToken)
        _                <- authState.set(AuthedUser(authedUserAccess))
      } yield ()
  }

  private def observed(service: AuthorizationService[ServiceTask]): AuthorizationService[Task] =
    (request: Request[Task]) => HttpErrorHandler.errorResponseHandler(service.auth(request))

  val local = ZLayer.derive[AuthorizationServiceImpl].project[AuthorizationService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
