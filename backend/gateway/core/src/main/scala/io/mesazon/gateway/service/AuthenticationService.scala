package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.smithy.SignUpEmailRequest
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object AuthenticationService {

  private final class AuthenticationServiceImpl(
      userManagementRepository: UserManagementRepository,
      emailValidator: ServiceValidator[EmailRaw, Email],
  ) extends smithy.AuthenticationService[ServiceTask] {

    /** HTTP POST /signup/email */
    override def signUpEmail(request: SignUpEmailRequest): ServiceTask[Unit] = for {
      _     <- ZIO.logDebug(s"Signing up user with email: ${request.email}")
      email <- emailValidator.validate(request.email)
      _     <- userManagementRepository.insertUserOnboardEmail(email, OnboardStage.EmailConfirmation).unit
    } yield ()
  }

  private def observed(
      service: smithy.AuthenticationService[ServiceTask]
  ): smithy.AuthenticationService[Task] =
    new smithy.AuthenticationService[Task] {

      /** HTTP POST /signup/email */
      override def signUpEmail(request: SignUpEmailRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpEmail(request))
    }

  val live = ZLayer.derive[AuthenticationServiceImpl] >>> ZLayer.fromFunction(observed)
}
