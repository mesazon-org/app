package io.mesazon.gateway.unit.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.JwtService.AuthedUserAccess
import io.mesazon.gateway.service.{AuthorizationService, JwtService, ServiceTask}
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.TokenArbitraries
import io.mesazon.testkit.base.{GatewayArbitraries, ZWordSpecBase}
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

class AuthorizationServiceSpec extends ZWordSpecBase, GatewayArbitraries, TokenArbitraries {

  "AuthorizationService" when {
    "authorize" should {
      "return a successful response" in new TestContext {
        val authedUser  = arbitrarySample[AuthedUser]
        val accessToken = arbitrarySample[AccessToken]

        val authedUserAccess = arbitrarySample[AuthedUserAccess].copy(
          userID = authedUser.userID
        )

        inSequence(
          jwtServiceMock.verifyAccessToken
            .expects(accessToken)
            .returningZIO(authedUserAccess),
          authStateMock.set.expects(authedUser).returnsZIOUnit,
        )

        val authorizationService = buildAuthorizationService

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.value)))

        authorizationService.auth(request).zioEither.isRight shouldBe true
      }

      "fail with Unauthorized when token is missing" in new TestContext {
        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
//          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))) //  Missing Authorization header

        val authorizationService = buildAuthorizationService

        val serviceError = authorizationService
          .auth(request)
          .zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.TokenMissing.type]
        serviceError.asInstanceOf[
          ServiceError.UnauthorizedError.TokenMissing.type
        ] shouldBe ServiceError.UnauthorizedError.TokenMissing
      }
    }
  }

  trait TestContext {

    val authStateMock  = mock[AuthState]
    val jwtServiceMock = mock[JwtService]

    def buildAuthorizationService: AuthorizationService[ServiceTask] = ZIO
      .service[AuthorizationService[ServiceTask]]
      .provide(
        AuthorizationService.local,
        ZLayer.succeed(authStateMock),
        ZLayer.succeed(jwtServiceMock),
      )
      .zioValue
  }
}
