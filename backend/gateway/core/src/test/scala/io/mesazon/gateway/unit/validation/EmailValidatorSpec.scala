package io.mesazon.gateway.unit.validation

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.{EmailValidator, ServiceValidator}
import io.mesazon.testkit.base.*
import zio.*

class EmailValidatorSpec extends ZWordSpecBase {

  "EmailValidator" when {
    "emailValidator" should {
      "successfully validate all valid email addresses" in {
        val validEmails = List(
          "s@s.c",
          " s.s@s.c",
          "s-s@s.c",
          "s_s@s.c ",
          "s+s@s.c",
          " s@S.c.c ",
          "s@s.c.c.c ",
          "Addsd23@dsd.com",
        )

        val emailValidator: ServiceValidator[EmailRaw, Email] = ZIO
          .service[ServiceValidator[EmailRaw, Email]]
          .provide(EmailValidator.emailValidatorLive)
          .zioValue

        ZIO.foreach(validEmails)(emailValidator.validate).zioEither shouldBe Right(
          validEmails.map(e => Email.assume(e.trim.toLowerCase))
        )
      }

      "fail to validate invalid email addresses" in {
        val invalidEmails = List(
          "s",
          "s@.c",
          "@s.c",
          " s@c.",
          "s@.c@s ",
          "s@.c.c. ",
          "s@.c .c.c.",
          " s@.c.c.c.c. ",
          "",
        )

        val emailValidator: ServiceValidator[EmailRaw, Email] = ZIO
          .service[ServiceValidator[EmailRaw, Email]]
          .provide(EmailValidator.emailValidatorLive)
          .zioValue

        invalidEmails.map(emailValidator.validate).map(_.zioEither.left.value.message).take(9) shouldBe List(
          "request validation error [InvalidFieldError(email,Email raw [s] formatted [s] is not valid format: null,List(s))]",
          "request validation error [InvalidFieldError(email,Email raw [s@.c] formatted [s@.c] is not valid format: null,List(s@.c))]",
          "request validation error [InvalidFieldError(email,Email raw [@s.c] formatted [@s.c] is not valid format: null,List(@s.c))]",
          "request validation error [InvalidFieldError(email,Email raw [ s@c.] formatted [s@c.] is not valid format: null,List( s@c.))]",
          "request validation error [InvalidFieldError(email,Email raw [s@.c@s ] formatted [s@.c@s] is not valid format: null,List(s@.c@s ))]",
          "request validation error [InvalidFieldError(email,Email raw [s@.c.c. ] formatted [s@.c.c.] is not valid format: null,List(s@.c.c. ))]",
          "request validation error [InvalidFieldError(email,Email raw [s@.c .c.c.] formatted [s@.c .c.c.] is not valid format: null,List(s@.c .c.c.))]",
          "request validation error [InvalidFieldError(email,Email raw [ s@.c.c.c.c. ] formatted [s@.c.c.c.c.] is not valid format: null,List( s@.c.c.c.c. ))]",
          "request validation error [InvalidFieldError(email,Email raw [] formatted [] is not valid format: null,List())]",
        )
      }
    }
  }
}
