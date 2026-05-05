package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class OnboardVerifyPhoneNumberPostRequestServiceValidator
    extends ServiceValidator[smithy.OnboardVerifyPhoneNumberPostRequest, OnboardVerifyPhoneNumber] {

  val domainValidator: DomainValidator[smithy.OnboardVerifyPhoneNumberPostRequest, OnboardVerifyPhoneNumber] = {
    onboardVerifyPhoneNumberPostRequest =>
      ZIO.succeed(
        (
          validateRequiredField("otpID", onboardVerifyPhoneNumberPostRequest.otpID, OtpID.either),
          validateRequiredField("otp", onboardVerifyPhoneNumberPostRequest.otp, Otp.either),
        ).mapN(OnboardVerifyPhoneNumber.apply)
      )
  }
}

object OnboardVerifyPhoneNumberPostRequestServiceValidator {

  val live = ZLayer.derive[OnboardVerifyPhoneNumberPostRequestServiceValidator]
}
