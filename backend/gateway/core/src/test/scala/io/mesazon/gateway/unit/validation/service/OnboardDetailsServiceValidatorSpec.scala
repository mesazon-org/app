package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.PhoneNumberDomainValidator
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import zio.*

class OnboardDetailsServiceValidatorSpec extends ZWordSpecBase, SmithyArbitraries {

  "OnboardDetailsServiceValidator" should {
    "successfully validate valid onboard password" in {
      val onboardDetailsServiceValidator = ZIO
        .service[OnboardDetailsServiceValidator]
        .provide(
          OnboardDetailsServiceValidator.live,
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
        )
        .zioValue

      val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest]
        .copy(
          phoneNumber = smithy.PhoneNumberRequest(
            phoneCountryCode = "+357",
            phoneNationalNumber = "99135215",
          )
        )

      onboardDetailsServiceValidator.validate(onboardDetailsRequest).zioValue shouldBe OnboardDetails(
        fullName = FullName.assume(onboardDetailsRequest.fullName),
        phoneNumber = PhoneNumber(
          phoneRegion = PhoneRegion.assume("CY"),
          phoneCountryCode = PhoneCountryCode.assume("+357"),
          phoneNationalNumber = PhoneNationalNumber.assume("99135215"),
          phoneNumberE164 = PhoneNumberE164.assume("+35799135215"),
        ),
      )
    }

    "fail to validate invalid onboard password" in {
      val onboardDetailsServiceValidator = ZIO
        .service[OnboardDetailsServiceValidator]
        .provide(
          OnboardDetailsServiceValidator.live,
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
        )
        .zioValue

      val onboardDetailsRequest = smithy.OnboardDetailsRequest(
        fullName = "",
        phoneNumber = smithy.PhoneNumberRequest(
          phoneCountryCode = "+123",
          phoneNationalNumber = "12345678",
        ),
      )

      onboardDetailsServiceValidator.validate(onboardDetailsRequest).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError(
              fieldName = "fullName",
              errorMessage = "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
              invalidValues = List(""),
            ),
            InvalidFieldError(
              fieldName = "phoneCountryCode",
              errorMessage =
                "PhoneRegion is not supported, phoneCountryCode: [+123], phoneNationalNumber: [12345678], supportedPhoneRegions: [CY,GB], error: [None]",
              invalidValues = List("+123"),
            ),
            InvalidFieldError(
              fieldName = "phoneNationalNumber",
              errorMessage =
                "PhoneNationalNumber could not be validated due to phone region is not supported, phoneCountryCode: [+123], phoneNationalNumber: [12345678], supportedPhoneRegions: [CY,GB], error: [None]",
              invalidValues = List("12345678"),
            ),
          )
        )
    }
  }
}
