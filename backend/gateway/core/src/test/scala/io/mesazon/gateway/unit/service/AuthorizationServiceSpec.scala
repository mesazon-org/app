package io.mesazon.gateway.unit.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.repository.domain.UserDetailsRow
import io.mesazon.gateway.service.JwtService.AuthedUserAccess
import io.mesazon.gateway.service.{AuthorizationService, JwtService, ServiceTask}
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.{RepositoryArbitraries, TokenArbitraries}
import io.mesazon.testkit.base.{GatewayArbitraries, ZWordSpecBase}
import org.http4s.*
import org.http4s.headers.Authorization
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

        authorizationService.auth(request, requiresCompletedOnboardStage = false).zioEither.isRight shouldBe true
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

        authorizationService.auth(request, requiresCompletedOnboardStage = true).zioEither.isRight shouldBe true
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
          .auth(request, requiresCompletedOnboardStage = true)
          .zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.FailedOnboardStage]
        serviceError.asInstanceOf[
          ServiceError.UnauthorizedError.FailedOnboardStage
        ] shouldBe ServiceError.UnauthorizedError.FailedOnboardStage(
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
          .auth(request, requiresCompletedOnboardStage = true)
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.asInstanceOf[
          ServiceError.InternalServerError.UnexpectedError
        ] shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"User details not found for user ID: ${authedUser.userID}"
        )
      }

      "fail with AuthorizationTokenMissing when token is missing for non required completed stage" in new TestContext {
        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
        //          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))) //  Missing Authorization header

        val authorizationService = buildAuthorizationService

        val serviceError = authorizationService
          .auth(request, requiresCompletedOnboardStage = false)
          .zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.AuthorizationTokenMissing.type]
        serviceError.asInstanceOf[
          ServiceError.UnauthorizedError.AuthorizationTokenMissing.type
        ] shouldBe ServiceError.UnauthorizedError.AuthorizationTokenMissing
      }
    }
  }

  trait TestContext {
    val authStateMock             = mock[AuthState]
    val jwtServiceMock            = mock[JwtService]
    val userDetailsRepositoryMock = mock[UserDetailsRepository]

    def buildAuthorizationService: AuthorizationService[ServiceTask] = ZIO
      .service[AuthorizationService[ServiceTask]]
      .provide(
        AuthorizationService.local,
        ZLayer.succeed(authStateMock),
        ZLayer.succeed(jwtServiceMock),
        ZLayer.succeed(userDetailsRepositoryMock),
      )
      .zioValue
  }
}
