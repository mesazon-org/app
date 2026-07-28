package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.*
import org.scalatest.DoNotDiscover
import sttp.model.*

@DoNotDiscover
class UserTokenRefreshApiSpec
    extends GatewayAcceptanceTest,
      SmithyArbitraries,
      UserTokenSmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  "User Token Refresh API" when {
    "POST /token/refresh" should {
      "successfully refresh user token with valid refresh token" in withContext { context =>
        import context.*

        val userID     = arbitrarySample[UserID]
        val refreshJwt = jwtService.generateRefreshToken(userID).zioValue

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            tokenID = refreshJwt.tokenID,
            userID = userID,
            tokenType = TokenType.RefreshToken,
          )

        postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow)).zioValue

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.InternalServerError](refreshJwt.refreshToken).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.Ok
        tokenRefreshPostResponse.body.value.accessTokenExpiresInSeconds should be > 0L

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue
        userTokenRowsAll should have size 1

        userTokenRowsAll.head.tokenID should not be refreshJwt.tokenID
        userTokenRowsAll.head.userID shouldBe userTokenRow.userID
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with BadRequest ValidationError when refresh token is missing" in withContext { context =>
        import context.*

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.ValidationError](RefreshToken.assume("")).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.BadRequest
        tokenRefreshPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("refreshToken"))

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized when refresh token is invalid" in withContext { context =>
        import context.*

        val invalidRefreshToken = RefreshToken.assume("invalid-refresh-token")

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.Unauthorized](invalidRefreshToken).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
        tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized when refresh token is valid but not found in database" in withContext { context =>
        import context.*

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            tokenType = TokenType.RefreshToken
          )

        // Note: we do not insert the user token row into database, so it will be missing when service tries to look it up
        val refreshJwt = jwtService.generateRefreshToken(userTokenRow.userID).zioValue

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.Unauthorized](refreshJwt.refreshToken).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
        tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }
    }
  }
}
