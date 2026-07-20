package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.*

class UserOnboardRequestValidatorSpec extends ZWordSpecBase, UserOnboardSmithyArbitraries {

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val passwordError =
    "Should match ^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$"

  private val validator: UserOnboardRequestValidator = ZIO
    .service[UserOnboardRequestValidator]
    .provide(
      UserOnboardRequestValidator.live,
      PhoneNumberDomainValidator.live,
      PhoneNumberUtil.live,
      ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
    )
    .zioValue

  "UserOnboardRequestValidator" should {
    "successfully validate a valid onboard password request" in {
      val onboardPasswordPostRequest = arbitrarySample[OnboardPasswordPostRequest]

      validator
        .validatedOnboardPasswordPostRequest(
          onboardPasswordPostRequest.transformInto[smithy.OnboardPasswordPostRequest]
        )
        .zioValue shouldBe onboardPasswordPostRequest
    }

    "fail to validate an invalid onboard password request" in {
      val onboardPasswordPostRequestSmithy = smithy.OnboardPasswordPostRequest(password = "short")

      validator.validatedOnboardPasswordPostRequest(onboardPasswordPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("password", passwordError, List("short"))
          )
        )
    }

    "successfully validate a valid onboard details request" in {
      val onboardDetailsPostRequest = arbitrarySample[OnboardDetailsPostRequest]

      validator
        .validatedOnboardDetailsPostRequest(
          onboardDetailsPostRequest.transformInto[smithy.OnboardDetailsPostRequest]
        )
        .zioValue shouldBe onboardDetailsPostRequest
    }

    "accumulate every onboard details field error" in {
      val onboardDetailsPostRequestSmithy = smithy.OnboardDetailsPostRequest(
        fullName = "",
        phoneNumber = smithy.PhoneNumberRequest(phoneCountryCode = "+123", phoneNationalNumber = "12345678"),
      )

      validator.validatedOnboardDetailsPostRequest(onboardDetailsPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
            InvalidFieldError(
              "phoneCountryCode",
              "PhoneRegion is not supported, phoneCountryCode: [+123], phoneNationalNumber: [12345678], supportedPhoneRegions: [CY,GB], error: [None]",
              List("+123"),
            ),
            InvalidFieldError(
              "phoneNationalNumber",
              "PhoneNationalNumber could not be validated due to phone region is not supported, phoneCountryCode: [+123], phoneNationalNumber: [12345678], supportedPhoneRegions: [CY,GB], error: [None]",
              List("12345678"),
            ),
          )
        )
    }

    "successfully validate a valid onboard verify phone number request" in {
      val onboardVerifyPhoneNumberPostRequest = arbitrarySample[OnboardVerifyPhoneNumberPostRequest]

      validator
        .validatedOnboardVerifyPhoneNumberPostRequest(
          onboardVerifyPhoneNumberPostRequest.transformInto[smithy.OnboardVerifyPhoneNumberPostRequest]
        )
        .zioValue shouldBe onboardVerifyPhoneNumberPostRequest
    }

    "fail to validate an invalid onboard verify phone number request" in {
      val onboardVerifyPhoneNumberPostRequestSmithy =
        arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest].copy(otp = "invalid-otp")

      validator.validatedOnboardVerifyPhoneNumberPostRequest(onboardVerifyPhoneNumberPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("otp", "Should match ^[A-Z0-9]{6}$", List("invalid-otp"))
          )
        )
    }
  }
}
