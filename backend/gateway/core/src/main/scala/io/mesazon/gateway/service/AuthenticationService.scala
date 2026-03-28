package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.smithy.SignUpEmailRequest
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{HttpErrorHandler, smithy}
import zio.*

object AuthenticationService {

  private final class AuthenticationServiceImpl(
      userManagementRepository: UserManagementRepository,
      emailValidator: ServiceValidator[EmailRaw, Email],
      emailClient: EmailClient,
  ) extends smithy.AuthenticationService[ServiceTask] {

    /** HTTP POST /signup/email */
    override def signUpEmail(request: SignUpEmailRequest): ServiceTask[Unit] = (for {
      _     <- ZIO.logDebug(s"Signing up user with email: ${request.email}")
      email <- emailValidator.validate(request.email)
      _     <- userManagementRepository.insertUserOnboardEmail(email, OnboardStage.EmailConfirmation).unit
      _     <- emailClient
        .sendEmailVerificationEmail(email, "")
        .retry(Schedule.exponential(100.millis) && Schedule.recurs(3))
    } yield ()).catchSome { case error: ServiceError.ConflictError.UserAlreadyExists =>
      ZIO.logDebug(s"User with the provided email already exists. ${error.message}") *>
        ZIO.unit
    }
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
