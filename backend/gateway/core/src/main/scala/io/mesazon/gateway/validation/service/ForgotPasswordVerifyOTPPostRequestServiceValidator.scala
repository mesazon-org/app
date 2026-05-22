package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class ForgotPasswordVerifyOTPPostRequestServiceValidator
    extends ServiceValidator[smithy.ForgotPasswordVerifyOTPPostRequest, ForgotPasswordVerifyOTP] {

  val domainValidator: DomainValidator[smithy.ForgotPasswordVerifyOTPPostRequest, ForgotPasswordVerifyOTP] = {
    forgotPasswordVerifyOTPPostRequest =>
      ZIO.succeed(
        (
          validateRequiredField("otpID", forgotPasswordVerifyOTPPostRequest.otpID, OtpID.either),
          validateRequiredField("otp", forgotPasswordVerifyOTPPostRequest.otp, Otp.either),
        ).mapN(ForgotPasswordVerifyOTP.apply)
      )
  }
}

object ForgotPasswordVerifyOTPPostRequestServiceValidator {

  val live = ZLayer.derive[ForgotPasswordVerifyOTPPostRequestServiceValidator]
}
