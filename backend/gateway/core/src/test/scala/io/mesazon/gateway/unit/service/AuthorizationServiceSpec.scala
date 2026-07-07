package io.mesazon.gateway.unit.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.{OrganizationUserRow, UserDetailsRow}
import io.mesazon.gateway.repository.{OrganizationManagementRepository, UserDetailsRepository}
import io.mesazon.gateway.service.JwtService.AuthedUserAccess
import io.mesazon.gateway.service.{AuthorizationService, JwtService, ServiceTask}
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.{RepositoryArbitraries, TokenArbitraries}
import io.mesazon.testkit.base.{GatewayArbitraries, ZWordSpecBase}
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import zio.*

class AuthorizationServiceSpec extends ZWordSpecBase, RepositoryArbitraries, GatewayArbitraries, TokenArbitraries {

  "AuthorizationService" when {
    "authorize" should {
      "return a successful response for non required completed stage" in new TestContext {
        val authedUser  = arbitrarySample[AuthedUser]
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          authStateMock.set.expects(authedUser).returnsZIOUnit.once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)))

        authorizationService
          .auth(request, requiresCompletedOnboardStage = false, organizationRolesAllowedOpt = None)
          .zioEither
          .isRight shouldBe true
      }

      "return a successful response for required completed stage" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val authedUser = arbitrarySample[AuthedUser]
          .copy(userID = userDetailsRow.userID)
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          authStateMock.set.expects(authedUser).returnsZIOUnit.once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)))

        authorizationService
          .auth(request, requiresCompletedOnboardStage = true, organizationRolesAllowedOpt = None)
          .zioEither
          .isRight shouldBe true
      }

      "fail with FailedOnboardStage when user has not completed required onboard stage" in new TestContext {
        val onboardStage   = Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val authedUser = arbitrarySample[AuthedUser]
          .copy(userID = userDetailsRow.userID)
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)))

        val serviceError = authorizationService
          .auth(request, requiresCompletedOnboardStage = true, organizationRolesAllowedOpt = None)
          .zioError

        serviceError shouldBe a[ServiceError.ForbiddenError.FailedOnboardStage]
        serviceError.asInstanceOf[
          ServiceError.ForbiddenError.FailedOnboardStage
        ] shouldBe ServiceError.ForbiddenError.FailedOnboardStage(
          userID = userDetailsRow.userID,
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.completedStages,
        )
      }

      "fail with Unexpected when user details are not found for required completed stage" in new TestContext {
        val authedUser  = arbitrarySample[AuthedUser]
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)))

        val serviceError = authorizationService
          .auth(request, requiresCompletedOnboardStage = true, organizationRolesAllowedOpt = None)
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.asInstanceOf[
          ServiceError.InternalServerError.UnexpectedError
        ] shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"User details not found for user ID: ${authedUser.userID}"
        )
      }

      "return a successful response when user has an allowed organization role" in new TestContext {
        val authedUser  = arbitrarySample[AuthedUser]
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        val organizationRolesAllowed = List(UserRole.Owner, UserRole.Admin)
        val organizationUserRow      = arbitrarySample[OrganizationUserRow].copy(
          userID = authedUser.userID,
          userRole = Random.shuffle(organizationRolesAllowed).zioValue.head,
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          organizationManagementRepositoryMock.getOrganizationUser
            .expects(organizationUserRow.organizationID, authedUser.userID)
            .returningZIO(Some(organizationUserRow))
            .once(),
          authStateMock.set.expects(authedUser).returnsZIOUnit.once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)),
            Header.Raw(CIString("X-Organization-ID"), organizationUserRow.organizationID.value.toString),
          )

        authorizationService
          .auth(
            request,
            requiresCompletedOnboardStage = false,
            organizationRolesAllowedOpt = Some(organizationRolesAllowed),
          )
          .zioEither
          .isRight shouldBe true
      }

      "fail with AuthHeaderMissingError when organization roles are required but header is missing" in new TestContext {
        val authedUser  = arbitrarySample[AuthedUser]
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once()
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)))

        val serviceError = authorizationService
          .auth(
            request,
            requiresCompletedOnboardStage = false,
            organizationRolesAllowedOpt = Some(List(UserRole.Owner, UserRole.Admin)),
          )
          .zioError

        serviceError shouldBe ServiceError.BadRequestError.AuthHeaderMissingError(
          AuthorizationService.OrganizationIDHeader.toString
        )
      }

      "fail with AuthorizationError when the header is not a valid UUID" in new TestContext {
        val accessToken = arbitrarySample[AccessToken]

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)),
            Header.Raw(CIString("X-Organization-ID"), "not-a-uuid"),
          )

        val serviceError = authorizationService
          .auth(
            request,
            requiresCompletedOnboardStage = false,
            organizationRolesAllowedOpt = Some(List(UserRole.Owner, UserRole.Admin)),
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.AuthorizationError]
      }

      "fail with FailedOrganizationRole when user is not assigned to the organization" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val accessToken    = arbitrarySample[AccessToken]
        val organizationID = arbitrarySample[OrganizationID]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        val organizationRolesAllowed = List(UserRole.Owner, UserRole.Admin)

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          organizationManagementRepositoryMock.getOrganizationUser
            .expects(organizationID, authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)),
            Header.Raw(CIString("X-Organization-ID"), organizationID.value.toString),
          )

        val serviceError = authorizationService
          .auth(
            request,
            requiresCompletedOnboardStage = false,
            organizationRolesAllowedOpt = Some(organizationRolesAllowed),
          )
          .zioError

        serviceError shouldBe ServiceError.ForbiddenError.FailedOrganizationRole(
          organizationID,
          authedUser.userID,
          organizationRolesAllowed,
        )
      }

      "fail with FailedOrganizationRole when user is assigned to the organization with a disallowed role" in new TestContext {
        val authedUser  = arbitrarySample[AuthedUser]
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        val organizationRolesAllowed = List(UserRole.Owner, UserRole.Admin)
        val organizationUserRow      = arbitrarySample[OrganizationUserRow].copy(
          userID = authedUser.userID,
          userRole = UserRole.User,
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess)
            .once(),
          organizationManagementRepositoryMock.getOrganizationUser
            .expects(organizationUserRow.organizationID, authedUser.userID)
            .returningZIO(Some(organizationUserRow))
            .once(),
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)),
            Header.Raw(CIString("X-Organization-ID"), organizationUserRow.organizationID.value.toString),
          )

        val serviceError = authorizationService
          .auth(
            request,
            requiresCompletedOnboardStage = false,
            organizationRolesAllowedOpt = Some(organizationRolesAllowed),
          )
          .zioError

        serviceError shouldBe ServiceError.ForbiddenError.FailedOrganizationRole(
          organizationUserRow.organizationID,
          authedUser.userID,
          organizationRolesAllowed,
        )
      }

      "fail with AuthHeaderMissingError when the authorization header is missing for non required completed stage" in new TestContext {
        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
        //          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))) //  Missing Authorization header

        val authorizationService = buildAuthorizationService

        val serviceError = authorizationService
          .auth(request, requiresCompletedOnboardStage = false, organizationRolesAllowedOpt = None)
          .zioError

        serviceError shouldBe ServiceError.BadRequestError.AuthHeaderMissingError(
          Authorization.name.toString
        )
      }
    }
  }

  trait TestContext {
    val authStateMock                        = mock[AuthState]
    val jwtServiceMock                       = mock[JwtService]
    val userDetailsRepositoryMock            = mock[UserDetailsRepository]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]

    def buildAuthorizationService: AuthorizationService[ServiceTask] = ZIO
      .service[AuthorizationService[ServiceTask]]
      .provide(
        AuthorizationService.local,
        ZLayer.succeed(authStateMock),
        ZLayer.succeed(jwtServiceMock),
        ZLayer.succeed(userDetailsRepositoryMock),
        ZLayer.succeed(organizationManagementRepositoryMock),
      )
      .zioValue
  }
}
