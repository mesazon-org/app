package io.mesazon.gateway.service

import io.mesazon.domain.gateway.{ServiceError, TokenType}
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.state.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserSignInService {

  private final class UserSignInServiceImpl(
      authState: AuthState,
      userDetailsRepository: UserDetailsRepository,
      userTokenRepository: UserTokenRepository,
      jwtService: JwtService,
  ) extends smithy.UserSignInService[ServiceTask] {

    /** HTTP POST /signin */
    override def signIn(): ServiceTask[smithy.SignInResponse] = for {
      authedUser  <- authState.get()
      userDetails <- userDetailsRepository
        .getUserDetails(authedUser.userID)
        .someOrFail(
          ServiceError.InternalServerError.UserNotFoundError(
            s"User details not found for userID: ${authedUser.userID}"
          )
        )
      accessJwt  <- jwtService.generateAccessToken(authedUser.userID)
      refreshJwt <- jwtService.generateRefreshToken(authedUser.userID)
      _          <- userTokenRepository.upsertUserToken(
        tokenID = refreshJwt.tokenID,
        userID = authedUser.userID,
        tokenType = TokenType.RefreshToken,
        expiresAt = refreshJwt.expiresAt,
      )
    } yield smithy.SignInResponse(
      accessTokenExpiresInSeconds = accessJwt.expiresIn.toSeconds,
      onboardStage = onboardStageFromDomainToSmithy(userDetails.onboardStage),
      refreshToken = refreshJwt.refreshToken.value,
      accessToken = accessJwt.accessToken.value,
    )
  }

  private def observed(
      service: smithy.UserSignInService[ServiceTask]
  ): smithy.UserSignInService[Task] =
    new smithy.UserSignInService[Task] {

      /** HTTP POST /signin */
      override def signIn(): Task[smithy.SignInResponse] = HttpErrorHandler.errorResponseHandler(service.signIn())
    }

  val live = ZLayer.derive[UserSignInServiceImpl] >>> ZLayer.fromFunction(observed)
}
