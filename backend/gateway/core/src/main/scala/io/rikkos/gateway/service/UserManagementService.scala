package io.rikkos.gateway.service

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.gateway.repository.UserRepository
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

    lazy val emptyUpdateUserDetailsRequest = smithy.UpdateUserDetailsRequest()

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authedUser         <- authorizationState.get()
        onboardUserDetails <- onboardUserDetailsRequestValidator.validate(request)
        _                  <- userRepository.insertUserDetails(authedUser.userID, authedUser.email, onboardUserDetails)
      } yield ()

    override def updateUser(request: smithy.UpdateUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _ <- ZIO.logDebug(s"Updating user with request: $request")
        _ <-
          if (emptyUpdateUserDetailsRequest == request)
            ZIO.fail(ServiceError.BadRequestError.NoEffect(s"update user details contains no update $request"))
          else ZIO.unit
        authedUser        <- authorizationState.get()
        updateUserDetails <- updateUserDetailsRequestValidator.validate(request)
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

  val live = ZLayer.fromFunction(UserManagementServiceImpl.apply) >>> ZLayer.fromFunction(observed)
}
