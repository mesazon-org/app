package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.state.*
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

trait AuthorizationService[F[_]] {
  def auth(request: Request[Task], requiresCompletedOnboardStage: Boolean): F[Unit]
}

object AuthorizationService {

  private final class AuthorizationServiceImpl(
      authState: AuthState,
      jwtService: JwtService,
      userDetailsRepository: UserDetailsRepository,
  ) extends AuthorizationService[ServiceTask] {

    override def auth(request: Request[Task], requiresCompletedOnboardStage: Boolean): ServiceTask[Unit] =
      for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        accessToken <- ZIO
          .getOrFailWith(ServiceError.UnauthorizedError.AuthorizationTokenMissing)(maybeBearerToken)
          .flatMap(accessTokenRaw =>
            ZIO
              .fromEither(AccessToken.either(accessTokenRaw))
              .mapError(error =>
                ServiceError.InternalServerError.AuthorizationError(s"Failed to apply AccessToken: $error")
              )
          )
        authedUserAccess <- jwtService.verifyAccessToken(accessToken)
        _                <-
          if (requiresCompletedOnboardStage)
            for {
              userDetails <- userDetailsRepository
                .getUserDetails(authedUserAccess.userID)
                .someOrFail(
                  ServiceError.InternalServerError
                    .UnexpectedError(s"User details not found for user ID: ${authedUserAccess.userID}")
                )
              _ <- verifyOnboardStage(
                userDetails.onboardStage,
                OnboardStage.completedStages,
              )
            } yield ()
          else ZIO.unit
        _ <- authState.set(AuthedUser(authedUserAccess.userID))
      } yield ()
  }

  private def observed(service: AuthorizationService[ServiceTask]): AuthorizationService[Task] =
    (request: Request[Task], requiresCompletedOnboardStage: Boolean) =>
      HttpErrorHandler.errorResponseHandler(service.auth(request, requiresCompletedOnboardStage))

  val local = ZLayer.derive[AuthorizationServiceImpl].project[AuthorizationService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
