package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import zio.*

class OnboardPasswordPostRequestServiceValidatorSpec extends ZWordSpecBase, SmithyArbitraries {

  "OnboardPasswordPostRequestServiceValidator" should {
    "successfully validate valid onboard password" in {
      val onboardPasswordServiceValidator = ZIO
        .service[OnboardPasswordPostRequestServiceValidator]
        .provide(
          OnboardPasswordPostRequestServiceValidator.live
        )
        .zioValue

      val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]

      onboardPasswordServiceValidator.validate(onboardPasswordPostRequest).zioValue shouldBe OnboardPassword(
        password = Password.assume(onboardPasswordPostRequest.password)
      )
    }

    "fail to validate invalid onboard password" in {
      val onboardPasswordServiceValidator = ZIO
        .service[OnboardPasswordPostRequestServiceValidator]
        .provide(
          OnboardPasswordPostRequestServiceValidator.live
        )
        .zioValue

      val invalidOnboardPasswordRequest = smithy.OnboardPasswordPostRequest(password = "short")

      onboardPasswordServiceValidator.validate(invalidOnboardPasswordRequest).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError(
              fieldName = "password",
              errorMessage =
                "Should match ^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$",
              invalidValues = List("short"),
            )
          )
        )
    }
  }
}
