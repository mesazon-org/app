package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.gateway.{Email, ServiceError, SignUpEmail}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import zio.*

class SignUpEmailServiceValidatorSpec extends ZWordSpecBase, SmithyArbitraries {

  "SignUpEmailServiceValidator" should {
    "successfully validate valid email addresses" in {
      val signUpEmailServiceValidator = ZIO
        .service[SignUpEmailServiceValidator]
        .provide(
          SignUpEmailServiceValidator.live,
          EmailDomainValidator.live,
        )
        .zioValue

      val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

      signUpEmailServiceValidator.validate(signUpEmailRequest).zioValue shouldBe SignUpEmail(
        email = Email.assume(signUpEmailRequest.email)
      )
    }

    "fail to validate invalid data" in {
      val signUpEmailServiceValidator = ZIO
        .service[SignUpEmailServiceValidator]
        .provide(
          SignUpEmailServiceValidator.live,
          EmailDomainValidator.live,
        )
        .zioValue

      val invalidSignUpEmailRequest = smithy.SignUpEmailRequest(email = "invalid-email")

      signUpEmailServiceValidator.validate(invalidSignUpEmailRequest).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError(
              fieldName = "email",
              errorMessage = "Invalid email format: [invalid-email], error: [null]",
              invalidValues = List("invalid-email"),
            )
          )
        )
    }
  }
}
