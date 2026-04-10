package io.mesazon.gateway.validation

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import zio.{ZIO, ZLayer}

object VerifyEmailValidator {

  private val verifyEmailDomainValidatorLive: DomainValidator[smithy.VerifyEmailRequest, VerifyEmail] = { request =>
    ZIO.succeed(
      (
        validateRequiredField("otpID", request.otpID, OtpID.either),
        validateRequiredField("otp", request.otp, Otp.either),
      ).mapN(VerifyEmail.apply)
    )
  }

  val verifyEmailValidatorLive = ZLayer.succeed(toServiceValidator(verifyEmailDomainValidatorLive))
}
