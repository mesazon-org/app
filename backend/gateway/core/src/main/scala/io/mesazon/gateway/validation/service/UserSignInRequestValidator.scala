package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.service.AuthenticationService.BasicCredentialsRequest
import io.mesazon.gateway.validation.domain.*
import zio.*

final class UserSignInRequestValidator(emailValidator: EmailValidator) {

  def validatedBasicCredentialsRequest(
      request: BasicCredentialsRequest
  ): IO[ServiceError.BadRequestError.ValidationError, BasicCredentials] =
    toValidatedRequestIO(validateBasicCredentials(request))

  private def validateBasicCredentials(
      request: BasicCredentialsRequest
  ): UIO[ValidatedNec[InvalidFieldError, BasicCredentials]] =
    emailValidator
      .validate(request.email, Email.either)
      .map(emailValidated =>
        (
          emailValidated,
          validateRequiredField("password", request.password, Password.either),
        ).mapN(BasicCredentials.apply)
      )
}

object UserSignInRequestValidator {

  val live = ZLayer.derive[UserSignInRequestValidator]
}
