package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.gateway.auth.{AuthorizationState, JwtService}
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.smithy.SignInResponse
import io.mesazon.gateway.{HttpErrorHandler, smithy}
import zio.*

object UserSignInService {

  private final class UserSignInServiceImpl(
      authorizationState: AuthorizationState,
      userDetailsRepository: UserDetailsRepository,
      userTokenRepository: UserTokenRepository,
      jwtService: JwtService,
      timeProvider: TimeProvider,
  ) extends smithy.UserSignInService[ServiceTask] {

    /** HTTP POST /signin */
    override def signIn(): ServiceTask[smithy.SignInResponse] = for {
      authedUser <- authorizationState.get()
      userDetails <- userDetailsRepository.getUserDetails(authedUser.userID)
      accessJwt <- jwtService.generateAccessToken(authedUser.userID)
    } yield smithy.SignInResponse(

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
