package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class SignUpVerifyEmailPostRequestServiceValidator
    extends ServiceValidator[smithy.SignUpVerifyEmailPostRequest, SignUpVerifyEmail] {

  val domainValidator: DomainValidator[smithy.SignUpVerifyEmailPostRequest, SignUpVerifyEmail] = {
    signUpVerifyEmailPostRequest =>
      ZIO.succeed(
        (
          validateRequiredField("otpID", signUpVerifyEmailPostRequest.otpID, OtpID.either),
          validateRequiredField("otp", signUpVerifyEmailPostRequest.otp, Otp.either),
        ).mapN(SignUpVerifyEmail.apply)
      )
  }
}

object SignUpVerifyEmailPostRequestServiceValidator {

  val live = ZLayer.derive[SignUpVerifyEmailPostRequestServiceValidator]
}
