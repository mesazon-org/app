package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class OnboardDetailsServiceValidator(phoneNumberValidator: PhoneNumberDomainValidator)
    extends ServiceValidator[smithy.OnboardDetailsRequest, OnboardDetails] {

  val domainValidator: DomainValidator[smithy.OnboardDetailsRequest, OnboardDetails] = { onboardDetailsRequest =>
    phoneNumberValidator
      .validate(
        (onboardDetailsRequest.phoneNumber.phoneCountryCode, onboardDetailsRequest.phoneNumber.phoneNationalNumber)
      )
      .map(phoneNumberValidated =>
        (
          validateRequiredField("fullName", onboardDetailsRequest.fullName, FullName.either),
          phoneNumberValidated,
        ).mapN(OnboardDetails.apply)
      )
  }
}

object OnboardDetailsServiceValidator {

  val live = ZLayer.derive[OnboardDetailsServiceValidator]
}
