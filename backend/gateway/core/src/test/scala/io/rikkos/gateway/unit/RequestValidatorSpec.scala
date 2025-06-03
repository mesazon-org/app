package io.rikkos.gateway.unit

import io.rikkos.domain.OnboardUserDetails
import io.rikkos.domain.ServiceError.BadRequestError
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.gateway.validation.RequestValidator.*
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*

class RequestValidatorSpec extends ZWordSpecBase, GatewayArbitraries {

  "RequestValidator" when {
    "validating OnboardUserRequest" should {
      "return OnboardUserDetails when all fields are valid" in forAll { (onboardUserDetails: OnboardUserDetails) =>
        val onboardUserDetailsRequest =
          onboardUserDetails.transformInto[smithy.OnboardUserDetailsRequest]

        onboardUserDetailsRequest.validate[OnboardUserDetails].zioValue shouldBe onboardUserDetails
      }

      "return all invalid fields when 1 or more fail validation" in {
        val onboardUserDetailsRequest =
          arbitrarySample[smithy.OnboardUserDetailsRequest].copy(firstName = " ", lastName = "")

        onboardUserDetailsRequest.validate[OnboardUserDetails].zioError shouldBe
          BadRequestError.RequestValidationError(
            List(
              "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
              "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
            )
          )
      }
    }
  }
}
