package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class UserSignUpRequestValidator(emailValidator: EmailValidator) {

  def validatedSignUpEmailPostRequest(
      request: smithy.SignUpEmailPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, SignUpEmailPostRequest] =
    toValidatedRequestIO(validateSignUpEmail(request))

  def validatedSignUpVerifyEmailPostRequest(
      request: smithy.SignUpVerifyEmailPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, SignUpVerifyEmailPostRequest] =
    toValidatedRequestIO(validateSignUpVerifyEmail(request))

  private def validateSignUpEmail(
      request: smithy.SignUpEmailPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, SignUpEmailPostRequest]] =
    emailValidator.validate(request.email, Email.either).map(_.map(SignUpEmailPostRequest.apply))

  private def validateSignUpVerifyEmail(
      request: smithy.SignUpVerifyEmailPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, SignUpVerifyEmailPostRequest]] =
    ZIO.succeed(
      (
        validateRequiredField("otpID", request.otpID, OtpID.either),
        validateRequiredField("otp", request.otp, Otp.either),
      ).mapN(SignUpVerifyEmailPostRequest.apply)
    )
}

object UserSignUpRequestValidator {

  val live = ZLayer.derive[UserSignUpRequestValidator]
}
