package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class UserForgotPasswordRequestValidator(emailValidator: EmailValidator) {

  def validatedForgotPasswordPostRequest(
      request: smithy.ForgotPasswordPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, ForgotPasswordPostRequest] =
    toValidatedRequestIO(validateForgotPassword(request))

  def validatedForgotPasswordVerifyOTPPostRequest(
      request: smithy.ForgotPasswordVerifyOTPPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, ForgotPasswordVerifyOTPPostRequest] =
    toValidatedRequestIO(validateForgotPasswordVerifyOTP(request))

  def validatedForgotPasswordResetPostRequest(
      request: smithy.ForgotPasswordResetPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, ForgotPasswordResetPostRequest] =
    toValidatedRequestIO(validateForgotPasswordReset(request))

  private def validateForgotPassword(
      request: smithy.ForgotPasswordPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, ForgotPasswordPostRequest]] =
    emailValidator.validate(request.email, Email.either).map(_.map(ForgotPasswordPostRequest.apply))

  private def validateForgotPasswordVerifyOTP(
      request: smithy.ForgotPasswordVerifyOTPPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, ForgotPasswordVerifyOTPPostRequest]] =
    ZIO.succeed(
      (
        validateRequiredField("otpID", request.otpID, OtpID.either),
        validateRequiredField("otp", request.otp, Otp.either),
      ).mapN(ForgotPasswordVerifyOTPPostRequest.apply)
    )

  private def validateForgotPasswordReset(
      request: smithy.ForgotPasswordResetPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, ForgotPasswordResetPostRequest]] =
    ZIO.succeed(
      (
        validateRequiredField("resetPasswordToken", request.resetPasswordToken, ResetPasswordToken.either),
        validateRequiredField("password", request.password, Password.either),
      ).mapN(ForgotPasswordResetPostRequest.apply)
    )
}

object UserForgotPasswordRequestValidator {

  val live = ZLayer.derive[UserForgotPasswordRequestValidator]
}
