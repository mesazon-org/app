package io.rikkos.gateway.service

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.validation.RequestValidator.*
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserManagementService {

  final private class UserManagementServiceImpl(userRepository: UserRepository, authorizationState: AuthorizationState)
      extends smithy.UserManagementService[[A] =>> IO[ServiceError, A]] {

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authedUser         <- authorizationState.get()
        onboardUserDetails <- request.validate[OnboardUserDetails]
        _                  <- userRepository.insertUserDetails(authedUser.userID, authedUser.email, onboardUserDetails)
      } yield ()

    override def updateUser(request: smithy.UpdateUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                 <- ZIO.logDebug(s"Updating user with request: $request")
        authedUser        <- authorizationState.get()
        updateUserDetails <- request.validate[UpdateUserDetails]
        _                 <- userRepository.updateUserDetails(authedUser.userID, updateUserDetails)
      } yield ()
  }

  private def observed(
      service: smithy.UserManagementService[[A] =>> IO[ServiceError, A]]
  ): smithy.UserManagementService[Task] =
    new smithy.UserManagementService[Task] {
      override def onboardUser(request: smithy.OnboardUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.onboardUser(request))

      override def updateUser(request: smithy.UpdateUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.updateUser(request))
    }

  val live = ZLayer(
    for {
      userRepository     <- ZIO.service[UserRepository]
      authorizationState <- ZIO.service[AuthorizationState]
    } yield observed(new UserManagementServiceImpl(userRepository, authorizationState))
  )
}
