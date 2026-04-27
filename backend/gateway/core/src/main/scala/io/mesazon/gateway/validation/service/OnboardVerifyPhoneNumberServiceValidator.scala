package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class OnboardVerifyPhoneNumberServiceValidator
    extends ServiceValidator[smithy.OnboardVerifyPhoneNumberRequest, OnboardVerifyPhoneNumber] {

  val domainValidator: DomainValidator[smithy.OnboardVerifyPhoneNumberRequest, OnboardVerifyPhoneNumber] = {
    onboardVerifyPhoneNumberRequest =>
      ZIO.succeed(
        (
          validateRequiredField("otpID", onboardVerifyPhoneNumberRequest.otpID, OtpID.either),
          validateRequiredField("otp", onboardVerifyPhoneNumberRequest.otp, Otp.either),
        ).mapN(OnboardVerifyPhoneNumber.apply)
      )
  }
}

object OnboardVerifyPhoneNumberServiceValidator {

  val live = ZLayer.derive[OnboardVerifyPhoneNumberServiceValidator]
}
