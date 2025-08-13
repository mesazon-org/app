package io.rikkos.gateway.service

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.smithy.GetUserDetailsResponse
import io.rikkos.gateway.validation.ServiceValidator
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserManagementService {

  final private case class UserManagementServiceImpl(
      userRepository: UserRepository,
      authorizationState: AuthorizationState,
      onboardUserDetailsRequestValidator: ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails],
      updateUserDetailsRequestValidator: ServiceValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails],
  ) extends smithy.UserManagementService[[A] =>> IO[ServiceError, A]] {

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authedUser         <- authorizationState.get()
        onboardUserDetails <- onboardUserDetailsRequestValidator.validate(request)
        _                  <- userRepository.insertUserDetails(authedUser.userID, authedUser.email, onboardUserDetails)
      } yield ()

    override def updateUser(request: smithy.UpdateUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                 <- ZIO.logDebug(s"Updating user with request: $request")
        authedUser        <- authorizationState.get()
        updateUserDetails <- updateUserDetailsRequestValidator.validate(request)
        _                 <- userRepository.updateUserDetails(authedUser.userID, updateUserDetails)
      } yield ()

    override def getUser(userID: String): IO[ServiceError, smithy.GetUserDetailsResponse] =
      for {
        authedUser       <- authorizationState.get()
        _                <- ZIO.logDebug(s"Get user with userID: $userID")
        userDetailsTable <- userRepository.getUserDetails(authedUser.userID)
        userDetailsResponse = smithy.GetUserDetailsResponse(
          userDetailsTable.userID.value,
          userDetailsTable.email.value,
          userDetailsTable.firstName,
          userDetailsTable.lastName,
          userDetailsTable.phoneNumber,
          userDetailsTable.addressLine1,
          userDetailsTable.city,
          userDetailsTable.postalCode,
          userDetailsTable.company,
          userDetailsTable.createdAt,
          userDetailsTable.updatedAt,
          userDetailsTable.addressLine2,
        )
      } yield userDetailsResponse
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

      override def getUser(userID: String): Task[GetUserDetailsResponse] = ???
    }

  val live = ZLayer.fromFunction(UserManagementServiceImpl.apply) >>> ZLayer.fromFunction(observed)
}
