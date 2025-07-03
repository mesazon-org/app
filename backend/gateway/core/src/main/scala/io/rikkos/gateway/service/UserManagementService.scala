package io.rikkos.gateway.service

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.validation.DomainValidator
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import io.scalaland.chimney.dsl.*
import zio.*

object UserManagementService {

  final private case class UserManagementServiceImpl(
      userRepository: UserRepository,
      authorizationState: AuthorizationState,
      onboardUserDetailsRequestValidator: DomainValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails],
  ) extends smithy.UserManagementService[[A] =>> IO[ServiceError, A]] {

    override def onboardUser(request: smithy.OnboardUserDetailsRequest): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Onboarding user with request: $request")
        authedUser         <- authorizationState.get()
        onboardUserDetails <- onboardUserDetailsRequestValidator.validate(request)
        userDetails = onboardUserDetails
          .into[UserDetails]
          .withFieldConst(_.userID, authedUser.userID)
          .withFieldConst(_.email, authedUser.email)
          .transform
        _ <- userRepository.insertUserDetails(userDetails)
      } yield ()
  }

  private def observed(
      service: smithy.UserManagementService[[A] =>> IO[ServiceError, A]]
  ): smithy.UserManagementService[Task] =
    new smithy.UserManagementService[Task] {
      override def onboardUser(request: smithy.OnboardUserDetailsRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.onboardUser(request))
    }

  val live = ZLayer.fromFunction(UserManagementServiceImpl.apply) >>> ZLayer.fromFunction(observed)
}
