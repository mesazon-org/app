package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import zio.*

class OnboardPasswordServiceValidatorSpec extends ZWordSpecBase, SmithyArbitraries {

  "OnboardPasswordServiceValidator" should {
    "successfully validate valid onboard password" in {
      val onboardPasswordServiceValidator = ZIO
        .service[OnboardPasswordServiceValidator]
        .provide(
          OnboardPasswordServiceValidator.live
        )
        .zioValue

      val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest]

      onboardPasswordServiceValidator.validate(onboardPasswordRequest).zioValue shouldBe OnboardPassword(
        password = Password.assume(onboardPasswordRequest.password)
      )
    }

    "fail to validate invalid onboard password" in {
      val onboardPasswordServiceValidator = ZIO
        .service[OnboardPasswordServiceValidator]
        .provide(
          OnboardPasswordServiceValidator.live
        )
        .zioValue

      val invalidOnboardPasswordRequest = smithy.OnboardPasswordRequest(password = "short")

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
