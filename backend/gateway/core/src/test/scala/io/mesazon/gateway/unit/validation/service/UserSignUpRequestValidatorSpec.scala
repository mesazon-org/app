package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.*

class UserSignUpRequestValidatorSpec extends ZWordSpecBase, UserSignUpSmithyArbitraries {

  private val validator: UserSignUpRequestValidator = ZIO
    .service[UserSignUpRequestValidator]
    .provide(
      UserSignUpRequestValidator.live,
      EmailValidator.live,
    )
    .zioValue

  "UserSignUpRequestValidator" should {
    "successfully validate a valid sign up email request" in {
      val signUpEmailPostRequest = arbitrarySample[SignUpEmailPostRequest]

      validator
        .validatedSignUpEmailPostRequest(signUpEmailPostRequest.transformInto[smithy.SignUpEmailPostRequest])
        .zioValue shouldBe signUpEmailPostRequest
    }

    "fail to validate an invalid sign up email request" in {
      val signUpEmailPostRequestSmithy = smithy.SignUpEmailPostRequest(email = "invalid-email")

      validator.validatedSignUpEmailPostRequest(signUpEmailPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email"))
          )
        )
    }

    "successfully validate a valid sign up verify email request" in {
      val signUpVerifyEmailPostRequest = arbitrarySample[SignUpVerifyEmailPostRequest]

      validator
        .validatedSignUpVerifyEmailPostRequest(
          signUpVerifyEmailPostRequest.transformInto[smithy.SignUpVerifyEmailPostRequest]
        )
        .zioValue shouldBe signUpVerifyEmailPostRequest
    }

    "fail to validate an invalid sign up verify email request" in {
      val signUpVerifyEmailPostRequestSmithy =
        arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(otp = "invalid-otp")

      validator.validatedSignUpVerifyEmailPostRequest(signUpVerifyEmailPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("otp", "Should match ^[A-Z0-9]{6}$", List("invalid-otp"))
          )
        )
    }
  }
}
