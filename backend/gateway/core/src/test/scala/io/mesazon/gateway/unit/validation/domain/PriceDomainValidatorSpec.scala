package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.validation.domain.PriceDomainValidator
import io.mesazon.testkit.base.ZWordSpecBase
import org.scalatest.Inside.inside

class PriceDomainValidatorSpec extends ZWordSpecBase {

  private val priceDomainValidator = new PriceDomainValidator

  "PriceDomainValidator" should {
    "validate" should {
      "trim and uppercase an ISO currency while normalizing the amount to its exact fraction digits" in {
        inside(
          priceDomainValidator
            .validate((amountRaw = BigDecimal("12.30"), currencyRaw = " kwd "))
            .zioValue
            .toOption
        ) { case Some(priceValidated) =>
          priceValidated.amount.value shouldBe BigDecimal("12.300")
          priceValidated.amount.value.scale shouldBe 3
          priceValidated.currency.value shouldBe "KWD"
        }
      }

      "accept boundary scales, integer amounts, zero, and negative scales while normalizing upward" in {
        val pricesValidated = List(
          priceDomainValidator.validate((amountRaw = BigDecimal("1"), currencyRaw = "USD")).zioValue,
          priceDomainValidator.validate((amountRaw = BigDecimal("1.23"), currencyRaw = "USD")).zioValue,
          priceDomainValidator.validate((amountRaw = BigDecimal(0), currencyRaw = "JPY")).zioValue,
          priceDomainValidator.validate((amountRaw = BigDecimal("1.000"), currencyRaw = "KWD")).zioValue,
          priceDomainValidator.validate((amountRaw = BigDecimal(BigInt(12), -1), currencyRaw = "JPY")).zioValue,
        ).flatMap(_.toOption)

        pricesValidated.map(_.amount.value) shouldBe
          List(BigDecimal("1.00"), BigDecimal("1.23"), BigDecimal("0"), BigDecimal("1.000"), BigDecimal("120"))
        pricesValidated.map(_.amount.value.scale) shouldBe List(2, 2, 0, 3, 0)
      }

      "accumulate a negative amount and unsupported currency" in {
        priceDomainValidator.validate((amountRaw = BigDecimal(-1), currencyRaw = "not-a-currency")).zioValue.toEither.left.toOption.value.toNonEmptyList.toList shouldBe
          List(
            InvalidFieldError("amount", "Amount must be non-negative", List("-1")),
            InvalidFieldError("currency", "Unsupported ISO currency: [not-a-currency]", List("not-a-currency")),
          )
      }

      "reject unsupported, blank, and pseudo currencies" in {
        priceDomainValidator
          .validate((amountRaw = BigDecimal(1), currencyRaw = "   "))
          .zioValue
          .toEither
          .left
          .toOption
          .value
          .toNonEmptyList
          .toList shouldBe
          List(InvalidFieldError("currency", "Unsupported ISO currency: [   ]", List("   ")))

        priceDomainValidator
          .validate((amountRaw = BigDecimal(1), currencyRaw = "XXX"))
          .zioValue
          .toEither
          .left
          .toOption
          .value
          .toNonEmptyList
          .toList shouldBe
          List(
            InvalidFieldError(
              "currency",
              "Currency [XXX] does not have fixed fraction digits",
              List("XXX"),
            )
          )
      }

      "reject raw scales above each currency fraction-digit boundary without rounding or removing trailing zeros" in {
        val invalidFieldErrorsAmountExpected = List(
          InvalidFieldError("amount", "Amount scale [2] exceeds currency fraction digits [0]", List("1.00")),
          InvalidFieldError("amount", "Amount scale [3] exceeds currency fraction digits [2]", List("1.230")),
          InvalidFieldError("amount", "Amount scale [4] exceeds currency fraction digits [3]", List("1.0000")),
        )

        val invalidFieldErrorsAmount = List(
          priceDomainValidator.validate((amountRaw = BigDecimal("1.00"), currencyRaw = "JPY")).zioValue,
          priceDomainValidator.validate((amountRaw = BigDecimal("1.230"), currencyRaw = "USD")).zioValue,
          priceDomainValidator.validate((amountRaw = BigDecimal("1.0000"), currencyRaw = "KWD")).zioValue,
        ).flatMap(_.toEither.left.toOption.value.toNonEmptyList.toList)

        invalidFieldErrorsAmount shouldBe invalidFieldErrorsAmountExpected
      }
    }
  }
}
