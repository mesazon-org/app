package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class OnboardDetailsPostRequestServiceValidator(phoneNumberValidator: PhoneNumberDomainValidator)
    extends ServiceValidator[smithy.OnboardDetailsPostRequest, OnboardDetails] {

  val domainValidator: DomainValidator[smithy.OnboardDetailsPostRequest, OnboardDetails] = {
    onboardDetailsPostRequest =>
      phoneNumberValidator
        .validate(
          (
            onboardDetailsPostRequest.phoneNumber.phoneCountryCode,
            onboardDetailsPostRequest.phoneNumber.phoneNationalNumber,
          )
        )
        .map(phoneNumberValidated =>
          (
            validateRequiredField("fullName", onboardDetailsPostRequest.fullName, FullName.either),
            phoneNumberValidated,
          ).mapN(OnboardDetails.apply)
        )
  }
}

object OnboardDetailsPostRequestServiceValidator {

  val live = ZLayer.derive[OnboardDetailsPostRequestServiceValidator]
}
