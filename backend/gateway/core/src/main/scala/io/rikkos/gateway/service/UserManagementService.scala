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
      extends smithy.UserManagementService[[A] =>> IO[ServiceError, A]] {

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authedUser         <- authorizationState.get()
        onboardUserDetails <- request.validate[OnboardUserDetails]
        userDetails = UserDetails(
          authedUser.userID,
          authedUser.email,
          onboardUserDetails.firstName,
          onboardUserDetails.lastName,
          onboardUserDetails.countryCode,
          onboardUserDetails.phoneNumber,
          onboardUserDetails.addressLine1,
          onboardUserDetails.addressLine2,
          onboardUserDetails.city,
          onboardUserDetails.postalCode,
          onboardUserDetails.company,
        )
        _ <- userRepository.insertUserDetails(userDetails)
      } yield ()

    override def editUser(request: OnboardUserDetailsRequest): IO[ServiceError, Unit] = ???
  }

  private def observed(
      service: smithy.UserManagementService[[A] =>> IO[ServiceError, A]]
  ): smithy.UserManagementService[Task] =
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
