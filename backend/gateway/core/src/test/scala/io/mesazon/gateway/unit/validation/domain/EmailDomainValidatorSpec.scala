package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.testkit.base.*
import zio.*

class EmailDomainValidatorSpec extends ZWordSpecBase {

  "EmailDomainValidator" when {
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

        val emailDomainValidator = ZIO
          .service[EmailDomainValidator]
          .provide(EmailDomainValidator.live)
          .zioValue

        ZIO.foreach(validEmails)(emailDomainValidator.validate).zioValue.map(_.toEither.value) shouldBe
          validEmails.map(e => Email.assume(e.trim.toLowerCase))
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

        val emailDomainValidator = ZIO
          .service[EmailDomainValidator]
          .provide(EmailDomainValidator.live)
          .zioValue

        ZIO
          .foreach(invalidEmails)(emailDomainValidator.validate)
          .zioValue
          .map(_.toEither.left.value.map(_.errorMessage))
          .flatMap(_.toNonEmptyList.toList) shouldBe List(
          "Invalid email format: [s], error: [null]",
          "Invalid email format: [s@.c], error: [null]",
          "Invalid email format: [@s.c], error: [null]",
          "Invalid email format: [ s@c.], error: [null]",
          "Invalid email format: [s@.c@s ], error: [null]",
          "Invalid email format: [s@.c.c. ], error: [null]",
          "Invalid email format: [s@.c .c.c.], error: [null]",
          "Invalid email format: [ s@.c.c.c.c. ], error: [null]",
          "Invalid email format: [], error: [null]",
        )
      }
    }
  }
}
