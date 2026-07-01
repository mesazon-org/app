package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.state.*
import io.mesazon.gateway.tapir.TapirTask
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

trait AuthorizationService[F[_]] {
  def auth(request: Request[Task], requiresCompletedOnboardStage: Boolean): F[Unit]
  def auth(accessTokenRaw: String, requiresCompletedOnboardStage: Boolean): F[Unit]
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
        accessTokenRaw <- ZIO
          .getOrFailWith(ServiceError.UnauthorizedError.AuthorizationTokenMissing)(maybeBearerToken)
        _ <- auth(accessTokenRaw, requiresCompletedOnboardStage)
      } yield ()

    override def auth(accessTokenRaw: String, requiresCompletedOnboardStage: Boolean): ServiceTask[Unit] =
      for {
        accessToken <- ZIO
          .fromEither(AccessToken.either(accessTokenRaw))
          .mapError(error =>
            ServiceError.InternalServerError.AuthorizationError(s"Failed to apply AccessToken: $error")
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

  private def observedSmithy(service: AuthorizationService[ServiceTask]): AuthorizationService[Task] =
    new AuthorizationService[Task] {
      override def auth(request: Request[Task], requiresCompletedOnboardStage: Boolean): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.auth(request, requiresCompletedOnboardStage))

      override def auth(accessTokenRaw: String, requiresCompletedOnboardStage: Boolean): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.auth(accessTokenRaw, requiresCompletedOnboardStage))
    }

  private def observedTapir(service: AuthorizationService[ServiceTask]): AuthorizationService[TapirTask] =
    new AuthorizationService[TapirTask] {
      override def auth(request: Request[Task], requiresCompletedOnboardStage: Boolean): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(service.auth(request, requiresCompletedOnboardStage))

      override def auth(accessTokenRaw: String, requiresCompletedOnboardStage: Boolean): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(service.auth(accessTokenRaw, requiresCompletedOnboardStage))
    }

  val local = ZLayer.derive[AuthorizationServiceImpl].project[AuthorizationService[ServiceTask]](identity)

  val smithy = local >>> ZLayer.fromFunction(observedSmithy)

  val tapir = local >>> ZLayer.fromFunction(observedTapir)
}
