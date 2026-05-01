package io.mesazon.gateway.unit.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.mock.JwtServiceMock
import io.mesazon.gateway.service.{AuthorizationService, ServiceTask}
import io.mesazon.testkit.base.{GatewayArbitraries, ZWordSpecBase}
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

class AuthorizationServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  "AuthorizationService" when {
    "authorize" should {
      "return a successful response" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]
        val token      = "valid-token"
        val request    = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        val authorizationService = buildAuthorizationService(authedUser)

        authorizationService.auth(request).zioEither.isRight shouldBe true
      }

      "fail with Unauthorized when token is missing" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]
        val request    = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
//          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))) //  Missing Authorization header

        val authorizationService = buildAuthorizationService(authedUser)

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

  trait TestContext extends JwtServiceMock {
    def buildAuthorizationService(authedUser: AuthedUser): AuthorizationService[ServiceTask] = ZIO
      .service[AuthorizationService[ServiceTask]]
      .provide(
        AuthorizationService.local,
        Mocks.authStateLive(authedUser),
        jwtServiceMockLive(),
      )
      .zioValue
  }
}
