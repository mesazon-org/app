package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
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
import zio.*

@DoNotDiscover
class UserTokenRefreshApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      SmithyArbitraries,
      UserTokenSmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userTokenTable)
    }
  }

  "User Token Refresh API" when {
    "POST /token/refresh" should {
      "successfully refresh user token with valid refresh token and reject the old refresh token afterwards" in withContext {
        context =>
          import context.*

          for {
            userID = arbitrarySample[UserID]

            refreshJwt <- jwtService.generateRefreshToken(userID)

            userTokenRow = arbitrarySample[UserTokenRow]
              .copy(
                tokenID = refreshJwt.tokenID,
                userID = userID,
                tokenType = TokenType.RefreshToken,
              )

            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow))

            tokenRefreshPostResponse <- gatewayClient.tokenRefreshPost[smithy.InternalServerError](
              refreshJwt.refreshToken
            )

            _ = tokenRefreshPostResponse.code shouldBe StatusCode.Ok
            _ = tokenRefreshPostResponse.body.value.accessTokenExpiresInSeconds should be > 0L

            authedUserRefreshNew <- jwtService.verifyRefreshToken(
              RefreshToken.assume(tokenRefreshPostResponse.body.value.refreshToken)
            )

            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userTokenRowsAll should have size 1
            _ = userTokenRowsAll.head.tokenID should not be refreshJwt.tokenID
            _ = userTokenRowsAll.head.tokenID shouldBe authedUserRefreshNew.tokenID
            _ = userTokenRowsAll.head.userID shouldBe userTokenRow.userID
            _ = userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken

            tokenRefreshPostResponseOldRefreshToken <- gatewayClient.tokenRefreshPost[smithy.Unauthorized](
              refreshJwt.refreshToken
            )

            _ = tokenRefreshPostResponseOldRefreshToken.code shouldBe StatusCode.Unauthorized
            _ = tokenRefreshPostResponseOldRefreshToken.body.left.value shouldBe smithy.Unauthorized()

            userTokenRowsAllAfterOldRefreshToken <- postgresClient.executeQuery(
              userTokenQueries.getAllUserTokensTesting
            )

            _ = userTokenRowsAllAfterOldRefreshToken shouldBe userTokenRowsAll
          } yield ()
      }

      "fail with BadRequest ValidationError when refresh token is missing" in withContext { context =>
        import context.*

        for {
          tokenRefreshPostResponse <- gatewayClient.tokenRefreshPost[smithy.ValidationError](
            RefreshToken.assume("")
          )

          _ = tokenRefreshPostResponse.code shouldBe StatusCode.BadRequest
          _ = tokenRefreshPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("refreshToken"))

          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when refresh token is invalid" in withContext { context =>
        import context.*

        for {
          invalidRefreshToken = RefreshToken.assume("invalid-refresh-token")

          tokenRefreshPostResponse <- gatewayClient.tokenRefreshPost[smithy.Unauthorized](invalidRefreshToken)

          _ = tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
          _ = tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when refresh token is valid but not found in database" in withContext { context =>
        import context.*

        for {
          userTokenRow = arbitrarySample[UserTokenRow]
            .copy(
              tokenType = TokenType.RefreshToken
            )

          refreshJwt <- jwtService.generateRefreshToken(userTokenRow.userID)

          tokenRefreshPostResponse <- gatewayClient.tokenRefreshPost[smithy.Unauthorized](
            refreshJwt.refreshToken
          )

          _ = tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
          _ = tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when a stored reset-password token is sent as the refresh token" in withContext {
        context =>
          import context.*

          for {
            userID = arbitrarySample[UserID]

            resetPasswordJwt <- jwtService.generateResetPasswordToken(userID)

            userTokenRow = arbitrarySample[UserTokenRow]
              .copy(
                tokenID = resetPasswordJwt.tokenID,
                userID = userID,
                tokenType = TokenType.ResetPasswordToken,
              )

            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow))

            tokenRefreshPostResponse <- gatewayClient.tokenRefreshPost[smithy.Unauthorized](
              RefreshToken.assume(resetPasswordJwt.resetPasswordToken.value)
            )

            _ = tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
            _ = tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userTokenRowsAll shouldBe List(userTokenRow)
          } yield ()
      }
    }
  }
}
