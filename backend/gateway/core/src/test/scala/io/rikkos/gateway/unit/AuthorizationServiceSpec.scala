package io.rikkos.gateway.unit

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationService
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.smithy
import io.rikkos.testkit.base.*
import org.http4s.*
import org.http4s.headers.Authorization
import zio.*

class AuthorizationServiceSpec extends ZWordSpecBase, DomainArbitraries {

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

        authorizationService
          .auth(request)
          .zioError
          .asInstanceOf[smithy.Unauthorized] shouldBe smithy.Unauthorized()
      }
    }
  }

  trait TestContext {
    def buildAuthorizationService(authedUser: AuthedUser): AuthorizationService[Throwable] = ZIO
      .service[AuthorizationService[Throwable]]
      .provide(
        AuthorizationService.live,
        authorizationStateMockLive(authedUser),
      )
      .zioValue
  }
}
