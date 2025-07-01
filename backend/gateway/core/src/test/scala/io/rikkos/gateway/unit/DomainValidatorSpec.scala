package io.rikkos.gateway.unit

import io.rikkos.domain.OnboardUserDetails
import io.rikkos.domain.ServiceError.BadRequestError
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.gateway.validation.DomainValidator
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.ZIO

class DomainValidatorSpec extends ZWordSpecBase, GatewayArbitraries {

  "DomaintValidator" when {
    "onboardUserDetailsRequestValidator" should {
      "return OnboardUserDetails when all fields are valid" in forAll { (onboardUserDetails: OnboardUserDetails) =>
        val onboardUserDetailsRequest =
          onboardUserDetails.transformInto[smithy.OnboardUserDetailsRequest]

        val validator = ZIO
          .service[DomainValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(DomainValidator.liveOnboardUserDetailsRequestValidator)
          .zioValue

        validator.validate(onboardUserDetailsRequest).zioValue shouldBe onboardUserDetails
      }

      "return all invalid fields when 1 or more fail validation" in {
        val onboardUserDetailsRequest =
          arbitrarySample[smithy.OnboardUserDetailsRequest].copy(firstName = " ", lastName = "")

        val validator = ZIO
          .service[DomainValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(DomainValidator.liveOnboardUserDetailsRequestValidator)
          .zioValue

        validator.validate(onboardUserDetailsRequest).zioError shouldBe
          BadRequestError.FormValidationError(
            Seq(
              ("firstName", "Should not have leading or trailing whitespaces & Should have a minimum length of 1"),
              ("lastName", "Should not have leading or trailing whitespaces & Should have a minimum length of 1"),
            )
          )
      }
    }
  }
}
