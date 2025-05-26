package io.rikkos.gateway.service

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.smithy.OnboardUserDetailsRequest
import io.rikkos.gateway.validation.RequestValidator.*
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserManagementService {

  final private class UserManagementServiceImpl(userRepository: UserRepository, authorizationState: AuthorizationState)
      extends smithy.UserManagementService[Task] {

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): Task[Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authMember         <- authorizationState.get()
        onboardUserDetails <- request.validate[OnboardUserDetails]
        userDetails = UserDetails(
          authMember.memberID,
          authMember.email,
          onboardUserDetails.firstName,
          onboardUserDetails.lastName,
          onboardUserDetails.organization,
        )
        _ <- userRepository.insertUserDetails(userDetails)
      } yield ()
  }

  private def observed(service: smithy.UserManagementService[Task]): smithy.UserManagementService[Task] =
    new smithy.UserManagementService[Task] {
      override def onboardUser(request: OnboardUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.onboardUser(request))
    }

  val live = ZLayer(
    for {
      userRepository     <- ZIO.service[UserRepository]
      authorizationState <- ZIO.service[AuthorizationState]
    } yield observed(new UserManagementServiceImpl(userRepository, authorizationState))
  )
}
