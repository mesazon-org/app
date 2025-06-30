package io.rikkos.gateway.unit

import io.github.iltotore.iron.*
import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.BadRequestError
import io.rikkos.gateway.mock.phoneNumberValidatorMockLive
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.gateway.validation.ServiceValidator
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.ZIO

class ServiceValidatorSpec extends ZWordSpecBase, GatewayArbitraries {

  "ServiceValidator" when {
    "onboardUserDetailsRequestValidator" should {
      "return OnboardUserDetails when all fields are valid" in {
        val onboardUserDetails  = arbitrarySample[OnboardUserDetails]
        val phoneRegion         = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val phoneNationalNumber = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val onboardUserDetailsRequest =
          onboardUserDetails
            .into[smithy.OnboardUserDetailsRequest]
            .withFieldConst(_.phoneRegion, phoneRegion)
            .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
            .transform

        val validator = ZIO
          .service[ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(ServiceValidator.onboardUserDetailsRequestValidatorLive, phoneNumberValidatorMockLive())
          .zioValue

        validator.validate(onboardUserDetailsRequest).zioValue shouldBe onboardUserDetails.copy(phoneNumber =
          PhoneNumber(phoneNationalNumber)
        )
      }

      "return all invalid fields when 1 or more fail validation" in {
        val onboardUserDetailsRequest =
          arbitrarySample[smithy.OnboardUserDetailsRequest].copy(firstName = " ", lastName = "")

        val validator = ZIO
          .service[ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(ServiceValidator.onboardUserDetailsRequestValidatorLive, phoneNumberValidatorMockLive())
          .zioValue

        validator.validate(onboardUserDetailsRequest).zioError shouldBe
          BadRequestError.FormValidationError(
            Seq(
              ("firstName", "Should not have leading or trailing whitespaces & Should have a minimum length of 1"),
              ("lastName", "Should not have leading or trailing whitespaces & Should have a minimum length of 1"),
            )
          )
      }

      "return all invalid fields when all fail validation" in {
        val onboardUserDetailsRequest = smithy.OnboardUserDetailsRequest(
          firstName = "",
          lastName = "",
          phoneRegion = "",
          phoneNationalNumber = "",
          addressLine1 = "",
          city = "",
          postalCode = "",
          company = "",
          addressLine2 = Some(""),
        )

        val validator = ZIO
          .service[ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(ServiceValidator.onboardUserDetailsRequestValidatorLive, phoneNumberValidatorMockLive())
          .zioValue

        validator
          .validate(onboardUserDetailsRequest)
          .zioError
          .asInstanceOf[BadRequestError.FormValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq(
          "firstName",
          "lastName",
          "addressLine1",
          "addressLine2",
          "city",
          "postalCode",
          "company",
        )
      }
    }
  }
}
