package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.AuthorizationState
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserManagementService {

  private final class UserManagementServiceImpl(
      userManagementRepository: UserManagementRepository,
      authorizationState: AuthorizationState,
      onboardUserDetailsRequestValidator: ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails],
      updateUserDetailsRequestValidator: ServiceValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails],
  ) extends smithy.UserManagementService[ServiceTask] {

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authedUser         <- authorizationState.get()
        onboardUserDetails <- onboardUserDetailsRequestValidator.validate(request)
        _ <- userManagementRepository.insertUserDetails(authedUser.userID, authedUser.email, onboardUserDetails)
      } yield ()

    override def updateUser(request: smithy.UpdateUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                 <- ZIO.logDebug(s"Updating user with request: $request")
        authedUser        <- authorizationState.get()
        updateUserDetails <- updateUserDetailsRequestValidator.validate(request)
        _                 <- userManagementRepository.updateUserDetails(authedUser.userID, updateUserDetails)
      } yield ()
  }

  private def observed(
      service: smithy.UserManagementService[ServiceTask]
  ): smithy.UserManagementService[Task] =
    new smithy.UserManagementService[Task] {
      override def onboardUser(request: smithy.OnboardUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.onboardUser(request))

      override def updateUser(request: smithy.UpdateUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.updateUser(request))
    }

  val live = ZLayer.derive[UserManagementServiceImpl] >>> ZLayer.fromFunction(observed)
}
