package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.TokenRefreshPostRequestServiceValidator
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserTokenServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

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
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val jwtServiceMock          = mock[JwtService]
    val userTokenRepositoryMock = mock[UserTokenRepository]

    def buildUserTokenServiceLive: smithy.UserTokenService[ServiceTask] =
      ZIO
        .service[smithy.UserTokenService[ServiceTask]]
        .provide(
          UserTokenService.local,
          TokenRefreshPostRequestServiceValidator.live,
          ZLayer.succeed(userTokenRepositoryMock),
          ZLayer.succeed(jwtServiceMock),
        )
        .zioValue
  }

}

//class UserSignInServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {
//
//  "UserSignInService" when {
//    "signInEmailPost" should {
//      "successfully sign in a user" in new TestContext {
//        val authedUser     = arbitrarySample[AuthedUser]
//        val userDetailsRow = arbitrarySample[UserDetailsRow]
//          .copy(userID = authedUser.userID)
//
//        val userTokenRow = arbitrarySample[UserTokenRow]
//          .copy(
//            userID = authedUser.userID,
//            tokenType = TokenType.RefreshToken,
//          )
//
//        val refreshJwt = arbitrarySample[RefreshJwt]
//          .copy(
//            tokenID = userTokenRow.tokenID,
//            expiresAt = userTokenRow.expiresAt,
//          )
//
//        val accessJwt = arbitrarySample[AccessJwt]
//
//        inSequence(
//          (() => authStateMock.get)
//            .expects()
//            .returningZIO(authedUser)
//            .once(),
//          userDetailsRepositoryMock.getUserDetails
//            .expects(authedUser.userID)
//            .returningZIO(Some(userDetailsRow))
//            .once(),
//          jwtServiceMock.generateAccessToken
//            .expects(authedUser.userID)
//            .returningZIO(accessJwt)
//            .once(),
//          jwtServiceMock.generateRefreshToken
//            .expects(authedUser.userID)
//            .returningZIO(refreshJwt)
//            .once(),
//          userTokenRepositoryMock.upsertUserToken
//            .expects(userTokenRow.tokenID, userTokenRow.userID, userTokenRow.tokenType, userTokenRow.expiresAt, None)
//            .returnsZIOUnit
//            .once(),
//        )
//
//        val userSignInService = buildUserSignInServiceLive
//
//        val signInEmailPostResponse = userSignInService.signInPost().zioValue
//
//        signInEmailPostResponse shouldBe smithy.SignInPostResponse(
//          accessTokenExpiresInSeconds = accessJwt.expiresIn.toSeconds,
//          onboardStage = onboardStageFromDomainToSmithy(userDetailsRow.onboardStage),
//          refreshToken = refreshJwt.refreshToken.value,
//          accessToken = accessJwt.accessToken.value,
//        )
//      }
//
//      "fail with UserNotFoundError when user details not found" in new TestContext {
//        val authedUser = arbitrarySample[AuthedUser]
//
//        inSequence(
//          (() => authStateMock.get)
//            .expects()
//            .returningZIO(authedUser)
//            .once(),
//          userDetailsRepositoryMock.getUserDetails
//            .expects(authedUser.userID)
//            .returningZIO(None)
//            .once(),
//        )
//
//        val userSignInService = buildUserSignInServiceLive
//
//        val serviceError = userSignInService.signInPost().zioError
//
//        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
//        serviceError
//          .asInstanceOf[ServiceError.InternalServerError.UserNotFoundError] shouldBe ServiceError.InternalServerError
//          .UserNotFoundError(
//            s"User details not found for userID: [${authedUser.userID}]"
//          )
//      }
//    }
//  }
//
//  trait TestContext {
//
//    val authStateMock             = mock[AuthState]
//    val jwtServiceMock            = mock[JwtService]
//    val userDetailsRepositoryMock = mock[UserDetailsRepository]
//    val userTokenRepositoryMock   = mock[UserTokenRepository]
//
//    def buildUserSignInServiceLive: smithy.UserSignInService[ServiceTask] =
//      ZIO
//        .service[smithy.UserSignInService[ServiceTask]]
//        .provide(
//          UserSignInService.local,
//          ZLayer.succeed(authStateMock),
//          ZLayer.succeed(userDetailsRepositoryMock),
//          ZLayer.succeed(userTokenRepositoryMock),
//          ZLayer.succeed(jwtServiceMock),
//        )
//        .zioValue
//  }
//}
