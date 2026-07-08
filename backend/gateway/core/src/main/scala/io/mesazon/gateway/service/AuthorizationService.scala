package io.mesazon.gateway.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.repository.{OrganizationManagementRepository, UserDetailsRepository}
import io.mesazon.gateway.state.*
import io.mesazon.gateway.tapir.TapirTask
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import zio.*

trait AuthorizationService[F[_]] {
  def auth(
      request: Request[Task],
      requiresCompletedOnboardStage: Boolean,
      organizationRolesAllowedOpt: Option[List[UserRole]],
  ): F[Unit]

  def auth(
      accessToken: AccessToken,
      requiresCompletedOnboardStage: Boolean,
      organizationIDOpt: Option[OrganizationID],
      organizationRolesAllowedOpt: Option[List[UserRole]],
  ): F[Unit]
}

object AuthorizationService {

  val OrganizationIDHeader = CIString("X-Organization-ID")

  private final class AuthorizationServiceImpl(
      authState: AuthState,
      jwtService: JwtService,
      userDetailsRepository: UserDetailsRepository,
      organizationManagementRepository: OrganizationManagementRepository,
  ) extends AuthorizationService[ServiceTask] {

    override def auth(
        request: Request[Task],
        requiresCompletedOnboardStage: Boolean,
        organizationRolesAllowedOpt: Option[List[UserRole]],
    ): ServiceTask[Unit] =
      for {
        maybeBearerToken = request.headers
          .get[`Authorization`]
          .collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
        accessTokenRaw <- ZIO
          .getOrFailWith(ServiceError.BadRequestError.AuthHeaderMissingError(Authorization.name.toString))(
            maybeBearerToken
          )
        accessToken <- ZIO
          .fromEither(AccessToken.either(accessTokenRaw))
          .mapError(error =>
            ServiceError.InternalServerError.AuthorizationError(s"Failed to apply AccessToken: $error")
          )
        organizationIDOptRaw = request.headers.get(OrganizationIDHeader).map(_.head.value)
        organizationIDOpt <- ZIO
          .fromEither(organizationIDOptRaw.traverse(OrganizationID.eitherFromString))
          .mapError(error =>
            ServiceError.InternalServerError.AuthorizationError(
              s"Failed to apply OrganizationID from header: $error"
            )
          )
        _ <- auth(accessToken, requiresCompletedOnboardStage, organizationIDOpt, organizationRolesAllowedOpt)
      } yield ()

    override def auth(
        accessToken: AccessToken,
        requiresCompletedOnboardStage: Boolean,
        organizationIDOpt: Option[OrganizationID],
        organizationRolesAllowedOpt: Option[List[UserRole]],
    ): ServiceTask[Unit] =
      for {
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
                authedUserAccess.userID,
                userDetails.onboardStage,
                OnboardStage.completedStages,
              )
            } yield ()
          else ZIO.unit
        _ <- ZIO.foreachDiscard(organizationRolesAllowedOpt)(
          verifyOrganizationRole(authedUserAccess.userID, organizationIDOpt, _)
        )
        _ <- authState.set(AuthedUser(authedUserAccess.userID))
      } yield ()

    private def verifyOrganizationRole(
        userID: UserID,
        organizationIDOpt: Option[OrganizationID],
        organizationRolesAllowed: List[UserRole],
    ): ServiceTask[Unit] =
      for {
        organizationID <- ZIO.getOrFailWith(
          ServiceError.BadRequestError.AuthHeaderMissingError(OrganizationIDHeader.toString)
        )(organizationIDOpt)
        organizationUserRow <- organizationManagementRepository
          .getOrganizationUser(organizationID, userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Organization user not found for organization ID: [$organizationID] and user ID: [$userID]"
            )
          )
        _ <- ZIO.unlessDiscard(organizationRolesAllowed.contains(organizationUserRow.userRole))(
          ZIO.fail(
            ServiceError.ForbiddenError
              .FailedOrganizationRole(organizationID, userID, organizationUserRow.userRole, organizationRolesAllowed)
          )
        )
      } yield ()
  }

  private def observedSmithy(service: AuthorizationService[ServiceTask]): AuthorizationService[Task] =
    new AuthorizationService[Task] {
      override def auth(
          request: Request[Task],
          requiresCompletedOnboardStage: Boolean,
          organizationRolesAllowedOpt: Option[List[UserRole]],
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.auth(request, requiresCompletedOnboardStage, organizationRolesAllowedOpt)
        )

      override def auth(
          accessToken: AccessToken,
          requiresCompletedOnboardStage: Boolean,
          organizationIDOpt: Option[OrganizationID],
          organizationRolesAllowedOpt: Option[List[UserRole]],
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.auth(accessToken, requiresCompletedOnboardStage, organizationIDOpt, organizationRolesAllowedOpt)
        )
    }

  private def observedTapir(service: AuthorizationService[ServiceTask]): AuthorizationService[TapirTask] =
    new AuthorizationService[TapirTask] {
      override def auth(
          request: Request[Task],
          requiresCompletedOnboardStage: Boolean,
          organizationRolesAllowedOpt: Option[List[UserRole]],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.auth(request, requiresCompletedOnboardStage, organizationRolesAllowedOpt)
        )

      override def auth(
          accessToken: AccessToken,
          requiresCompletedOnboardStage: Boolean,
          organizationIDOpt: Option[OrganizationID],
          organizationRolesAllowedOpt: Option[List[UserRole]],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.auth(accessToken, requiresCompletedOnboardStage, organizationIDOpt, organizationRolesAllowedOpt)
        )
    }

  val local = ZLayer.derive[AuthorizationServiceImpl].project[AuthorizationService[ServiceTask]](identity)

  val smithy = local >>> ZLayer.fromFunction(observedSmithy)

  val tapir = local >>> ZLayer.fromFunction(observedTapir)
}
