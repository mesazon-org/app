package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserTokenService {

  private final class UserTokenServiceImpl(
      userTokenRepository: UserTokenRepository,
      jwtService: JwtService,
      userTokenRequestValidator: UserTokenRequestValidator,
  ) extends smithy.UserTokenService[ServiceTask] {

    /** HTTP POST /token/refresh */
    override def tokenRefreshPost(
        tokenRefreshPostRequestSmithy: smithy.TokenRefreshPostRequest
    ): ServiceTask[smithy.TokenRefreshPostResponse] =
      for {
        _                       <- ZIO.logDebug("Refreshing user token")
        tokenRefreshPostRequest <- userTokenRequestValidator.validatedTokenRefreshPostRequest(
          tokenRefreshPostRequestSmithy
        )
        authedUserRefresh <- jwtService.verifyRefreshToken(tokenRefreshPostRequest.refreshToken)
        userTokenRow      <- userTokenRepository
          .getUserToken(
            authedUserRefresh.tokenID,
            authedUserRefresh.userID,
            TokenType.RefreshToken,
          )
          .someOrFail(ServiceError.UnauthorizedError.TokenFailedAuthorization("Refresh token not found in database"))
        accessJwt  <- jwtService.generateAccessToken(authedUserRefresh.userID)
        refreshJwt <- jwtService.generateRefreshToken(authedUserRefresh.userID)
        _          <- userTokenRepository.upsertUserToken(
          tokenID = refreshJwt.tokenID,
          userID = authedUserRefresh.userID,
          tokenType = TokenType.RefreshToken,
          expiresAt = refreshJwt.expiresAt,
          tokenIDOptOld = Some(userTokenRow.tokenID),
        )
      } yield smithy.TokenRefreshPostResponse(
        refreshJwt.refreshToken.value,
        accessJwt.accessToken.value,
        accessJwt.expiresIn.toSeconds,
      )
  }

  private def observed(
      service: smithy.UserTokenService[ServiceTask]
  ): smithy.UserTokenService[Task] =
    new smithy.UserTokenService[Task] {

      /** HTTP POST /token/refresh */
      override def tokenRefreshPost(
          tokenRefreshPostRequestSmithy: smithy.TokenRefreshPostRequest
      ): Task[smithy.TokenRefreshPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.tokenRefreshPost(tokenRefreshPostRequestSmithy))
    }

  val local = ZLayer.derive[UserTokenServiceImpl].project[smithy.UserTokenService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
