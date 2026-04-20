package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class SignUpVerifyEmailServiceValidator
    extends ServiceValidator[smithy.SignUpVerifyEmailRequest, SignUpVerifyEmail] {

  val domainValidator: DomainValidator[smithy.SignUpVerifyEmailRequest, SignUpVerifyEmail] = {
    signUpVerifyEmailRequest =>
      ZIO.succeed(
        (
          validateRequiredField("otpID", signUpVerifyEmailRequest.otpID, OtpID.either),
          validateRequiredField("otp", signUpVerifyEmailRequest.otp, Otp.either),
        ).mapN(SignUpVerifyEmail.apply)
      )
  }
}

object SignUpVerifyEmailServiceValidator {

  val live = ZLayer.derive[SignUpVerifyEmailServiceValidator]
}
