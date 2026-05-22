package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.gateway.{Email, ServiceError, SignUpEmail}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import zio.*

class SignUpEmailPostRequestServiceValidatorSpec extends ZWordSpecBase, SmithyArbitraries {

  "SignUpEmailPostRequestServiceValidator" should {
    "successfully validate valid email addresses" in {
      val signUpEmailServiceValidator = ZIO
        .service[SignUpEmailPostRequestServiceValidator]
        .provide(
          SignUpEmailPostRequestServiceValidator.live,
          EmailDomainValidator.live,
        )
        .zioValue

      val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

      signUpEmailServiceValidator.validate(signUpEmailPostRequest).zioValue shouldBe SignUpEmail(
        email = Email.assume(signUpEmailPostRequest.email)
      )
    }

    "fail to validate invalid data" in {
      val signUpEmailServiceValidator = ZIO
        .service[SignUpEmailPostRequestServiceValidator]
        .provide(
          SignUpEmailPostRequestServiceValidator.live,
          EmailDomainValidator.live,
        )
        .zioValue

      val invalidSignUpEmailRequest = smithy.SignUpEmailPostRequest(email = "invalid-email")

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
