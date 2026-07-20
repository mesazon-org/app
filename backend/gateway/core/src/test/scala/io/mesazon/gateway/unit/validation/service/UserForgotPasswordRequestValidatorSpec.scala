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

class UserForgotPasswordRequestValidatorSpec extends ZWordSpecBase, UserForgotPasswordSmithyArbitraries {

  private val passwordError =
    "Should match ^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$"

  private val validator: UserForgotPasswordRequestValidator = ZIO
    .service[UserForgotPasswordRequestValidator]
    .provide(
      UserForgotPasswordRequestValidator.live,
      EmailValidator.live,
    )
    .zioValue

  "UserForgotPasswordRequestValidator" should {
    "successfully validate a valid forgot password request" in {
      val forgotPasswordPostRequest = arbitrarySample[ForgotPasswordPostRequest]

      validator
        .validatedForgotPasswordPostRequest(forgotPasswordPostRequest.transformInto[smithy.ForgotPasswordPostRequest])
        .zioValue shouldBe forgotPasswordPostRequest
    }

    "fail to validate an invalid forgot password request" in {
      val forgotPasswordPostRequestSmithy = smithy.ForgotPasswordPostRequest(email = "invalid-email")

      validator.validatedForgotPasswordPostRequest(forgotPasswordPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email"))
          )
        )
    }

    "successfully validate a valid forgot password verify OTP request" in {
      val forgotPasswordVerifyOTPPostRequest = arbitrarySample[ForgotPasswordVerifyOTPPostRequest]

      validator
        .validatedForgotPasswordVerifyOTPPostRequest(
          forgotPasswordVerifyOTPPostRequest.transformInto[smithy.ForgotPasswordVerifyOTPPostRequest]
        )
        .zioValue shouldBe forgotPasswordVerifyOTPPostRequest
    }

    "fail to validate an invalid forgot password verify OTP request" in {
      val forgotPasswordVerifyOTPPostRequestSmithy =
        arbitrarySample[smithy.ForgotPasswordVerifyOTPPostRequest].copy(otp = "invalid-otp")

      validator
        .validatedForgotPasswordVerifyOTPPostRequest(forgotPasswordVerifyOTPPostRequestSmithy)
        .zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("otp", "Should match ^[A-Z0-9]{6}$", List("invalid-otp"))
          )
        )
    }

    "successfully validate a valid forgot password reset request" in {
      val forgotPasswordResetPostRequest = arbitrarySample[ForgotPasswordResetPostRequest]

      validator
        .validatedForgotPasswordResetPostRequest(
          forgotPasswordResetPostRequest.transformInto[smithy.ForgotPasswordResetPostRequest]
        )
        .zioValue shouldBe forgotPasswordResetPostRequest
    }

    "fail to validate an invalid forgot password reset request" in {
      val forgotPasswordResetPostRequestSmithy =
        arbitrarySample[smithy.ForgotPasswordResetPostRequest].copy(password = "short")

      validator.validatedForgotPasswordResetPostRequest(forgotPasswordResetPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("password", passwordError, List("short"))
          )
        )
    }
  }
}
