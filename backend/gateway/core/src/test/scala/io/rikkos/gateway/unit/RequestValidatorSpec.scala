package io.rikkos.gateway.unit

import io.rikkos.domain.OnboardUserDetails
import io.rikkos.domain.ServiceError.BadRequestError
import io.rikkos.gateway.smithy
import io.rikkos.gateway.unit.utils.SmithyArbitraries
import io.rikkos.gateway.validation.RequestValidator.*
import io.rikkos.testkit.base.*

class RequestValidatorSpec extends ZWordSpecBase, DomainArbitraries, SmithyArbitraries {

  "RequestValidator" when {
    "validating OnboardUserRequest" should {
      "return OnboardUserDetails when all fields are valid" in forAll { (onboardUserDetails: OnboardUserDetails) =>
        val request = smithy.OnboardUserDetailsRequest(
          onboardUserDetails.firstName.value,
          onboardUserDetails.lastName.value,
          onboardUserDetails.organization.value,
        )

        request.validate[OnboardUserDetails].zioValue shouldBe onboardUserDetails
      }

      "return all invalid fields when 1 or more fail validation" in {
        val request = smithy.OnboardUserDetailsRequest(" ", "", "organization")

        request.validate[OnboardUserDetails].zioError shouldBe
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
