package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.validation.domain.EmailValidator
import io.mesazon.testkit.base.*
import zio.*

class EmailValidatorSpec extends ZWordSpecBase {

  private val emailValidator = ZIO
    .service[EmailValidator]
    .provide(EmailValidator.live)
    .zioValue

  "EmailValidator" when {
    "validate" should {
      "build the requested newtype for valid emails, trimmed and lower-cased" in {
        val validEmails = List("s@s.c", " s.s@s.c", "s-s@s.c", "s_s@s.c ", " Addsd23@DSD.com ")

        ZIO
          .foreach(validEmails)(emailValidator.validate(_, CustomerEmail.either))
          .zioValue
          .map(_.toEither.value) shouldBe validEmails.map(email => CustomerEmail.assume(email.trim.toLowerCase))
      }

      "fail with an invalid-format error for malformed emails" in {
        emailValidator
          .validate("not-an-email", CustomerEmail.either)
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("email", "Invalid email format: [not-an-email], error: [null]", Seq("not-an-email"))
        )
      }
    }

    "validateOptional" should {
      "return a valid None when no email is given" in {
        emailValidator.validateOptional(None, CustomerEmail.either).zioValue.toEither.value shouldBe None
      }

      "build the newtype when a valid email is given" in {
        emailValidator
          .validateOptional(Some(" A@B.c "), CustomerEmail.either)
          .zioValue
          .toEither
          .value shouldBe Some(CustomerEmail.assume("a@b.c"))
      }

      "fail for an invalid email" in {
        emailValidator
          .validateOptional(Some("bad"), CustomerEmail.either)
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("email", "Invalid email format: [bad], error: [null]", Seq("bad"))
        )
      }
    }
  }
}
