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

    override def editUser(request: smithy.EditUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _               <- ZIO.logDebug(s"Editing user with request: $request")
        authedUser      <- authorizationState.get()
        editUserDetails <- request.validate[EditUserDetails]
        userDetails = EditUserDetails(
          editUserDetails.userID,
          editUserDetails.firstName,
          editUserDetails.lastName,
          editUserDetails.countryCode,
          editUserDetails.phoneNumber,
          editUserDetails.addressLine1,
          editUserDetails.addressLine2,
          editUserDetails.city,
          editUserDetails.postalCode,
          editUserDetails.company,
        )
        _ <- userRepository.editUserDetails(userDetails)
      } yield ()
  }

  private def observed(
      service: smithy.UserManagementService[[A] =>> IO[ServiceError, A]]
  ): smithy.UserManagementService[Task] =
    new smithy.UserManagementService[Task] {
      override def onboardUser(request: smithy.OnboardUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.onboardUser(request))

      override def editUser(request: smithy.EditUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.editUser(request))
    }

  val live = ZLayer(
    for {
      userRepository     <- ZIO.service[UserRepository]
      authorizationState <- ZIO.service[AuthorizationState]
    } yield observed(new UserManagementServiceImpl(userRepository, authorizationState))
  )
}
