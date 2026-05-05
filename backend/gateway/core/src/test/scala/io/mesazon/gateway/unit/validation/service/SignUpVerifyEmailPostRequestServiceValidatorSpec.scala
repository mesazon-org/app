package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import zio.*

class SignUpVerifyEmailPostRequestServiceValidatorSpec extends ZWordSpecBase, SmithyArbitraries {

  "SignUpVerifyEmailPostRequestServiceValidator" should {
    "successfully validate valid" in {
      val signUpVerifyEmailServiceValidator = ZIO
        .service[SignUpVerifyEmailPostRequestServiceValidator]
        .provide(
          SignUpVerifyEmailPostRequestServiceValidator.live
        )
        .zioValue

      val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]

      signUpVerifyEmailServiceValidator.validate(signUpVerifyEmailPostRequest).zioValue shouldBe SignUpVerifyEmail(
        otp = Otp.assume(signUpVerifyEmailPostRequest.otp),
        otpID = OtpID.assume(signUpVerifyEmailPostRequest.otpID),
      )
    }

    "fail to validate invalid data" in {
      val signUpVerifyEmailServiceValidator = ZIO
        .service[SignUpVerifyEmailPostRequestServiceValidator]
        .provide(
          SignUpVerifyEmailPostRequestServiceValidator.live
        )
        .zioValue

      val invalidSignUpVerifyEmailRequest = smithy.SignUpVerifyEmailPostRequest(otpID = "", otp = "invalid-otp")

      signUpVerifyEmailServiceValidator.validate(invalidSignUpVerifyEmailRequest).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError(
              fieldName = "otpID",
              errorMessage = "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
              invalidValues = List(""),
            ),
            InvalidFieldError(
              fieldName = "otp",
              errorMessage = "Should match ^[A-Z0-9]{6}$",
              invalidValues = List("invalid-otp"),
            ),
          )
        )
    }
  }
}
