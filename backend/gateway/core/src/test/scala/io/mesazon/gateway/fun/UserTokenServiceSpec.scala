package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.UserTokenRequestValidator
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class UserTokenServiceSpec
    extends ZWordSpecBase,
      SmithyArbitraries,
      UserTokenSmithyArbitraries,
      RepositoryArbitraries,
      TokenArbitraries {

  "UserTokenService" when {
    "tokenRefreshPost" should {
      "successfully refresh tokens" in new TestContext {
        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            tokenType = TokenType.RefreshToken
          )

        val authedUserRefresh = arbitrarySample[AuthedUserRefresh]
          .copy(
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
          )

        val refreshToken = arbitrarySample[RefreshToken]
        val accessJwt    = arbitrarySample[AccessJwt]
        val refreshJwt   = arbitrarySample[RefreshJwt]

        inSequence(
          jwtServiceMock.verifyRefreshToken
            .expects(refreshToken)
            .returningZIO(authedUserRefresh)
            .once(),
          userTokenRepositoryMock.getUserToken
            .expects(userTokenRow.tokenID, userTokenRow.userID, TokenType.RefreshToken)
            .returningZIO(Some(userTokenRow))
            .once(),
          jwtServiceMock.generateAccessToken
            .expects(authedUserRefresh.userID)
            .returningZIO(accessJwt)
            .once(),
          jwtServiceMock.generateRefreshToken
            .expects(authedUserRefresh.userID)
            .returningZIO(refreshJwt)
            .once(),
          userTokenRepositoryMock.upsertUserToken
            .expects(
              refreshJwt.tokenID,
              authedUserRefresh.userID,
              TokenType.RefreshToken,
              refreshJwt.expiresAt,
              Some(userTokenRow.tokenID),
            )
            .returnsZIOUnit
            .once(),
        )

        val userTokenService = buildUserTokenServiceLive

        val tokenRefreshPostRequest = arbitrarySample[smithy.TokenRefreshPostRequest]
          .copy(
            refreshToken = refreshToken.value
          )

        val tokenRefreshPostResponse = userTokenService.tokenRefreshPost(tokenRefreshPostRequest).zioValue

        tokenRefreshPostResponse shouldBe smithy.TokenRefreshPostResponse(
          refreshJwt.refreshToken.value,
          accessJwt.accessToken.value,
          accessJwt.expiresIn.getSeconds,
        )
      }

      "fail with TokenFailedAuthorization when refresh token not found in database" in new TestContext {
        val authedUserRefresh = arbitrarySample[AuthedUserRefresh]

        val refreshToken = arbitrarySample[RefreshToken]

        inSequence(
          jwtServiceMock.verifyRefreshToken
            .expects(refreshToken)
            .returningZIO(authedUserRefresh)
            .once(),
          userTokenRepositoryMock.getUserToken
            .expects(authedUserRefresh.tokenID, authedUserRefresh.userID, TokenType.RefreshToken)
            .returningZIO(None)
            .once(),
        )

        val userTokenService = buildUserTokenServiceLive

        val tokenRefreshPostRequest = arbitrarySample[smithy.TokenRefreshPostRequest]
          .copy(
            refreshToken = refreshToken.value
          )

        val serviceError = userTokenService.tokenRefreshPost(tokenRefreshPostRequest).zioError

        serviceError shouldBe a[ServiceError.UnauthorizedError.TokenFailedAuthorization]
        serviceError
          .asInstanceOf[ServiceError.UnauthorizedError.TokenFailedAuthorization] shouldBe ServiceError.UnauthorizedError
          .TokenFailedAuthorization(
            "Refresh token not found in database"
          )
      }
    }
  }

  trait TestContext {
    val jwtServiceMock          = mock[JwtService]
    val userTokenRepositoryMock = mock[UserTokenRepository]

    def buildUserTokenServiceLive: smithy.UserTokenService[ServiceTask] =
      ZIO
        .service[smithy.UserTokenService[ServiceTask]]
        .provide(
          UserTokenService.local,
          UserTokenRequestValidator.live,
          ZLayer.succeed(userTokenRepositoryMock),
          ZLayer.succeed(jwtServiceMock),
        )
        .zioValue
  }
}
