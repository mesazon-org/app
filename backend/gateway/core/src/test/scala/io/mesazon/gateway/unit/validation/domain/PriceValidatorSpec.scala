package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.validation.domain.PriceValidator
import io.mesazon.testkit.base.ZWordSpecBase

class PriceValidatorSpec extends ZWordSpecBase {

  private val validator = new PriceValidator

  "PriceValidator" should {
    "normalize valid amounts to each currency's configured decimal places without rounding" in {
      val prices = List(
        validator.validate((amountRaw = BigDecimal("12"), currency = Currency.EUR)).zioValue,
        validator.validate((amountRaw = BigDecimal("12.3"), currency = Currency.USD)).zioValue,
        validator.validate((amountRaw = BigDecimal("12.34"), currency = Currency.GBP)).zioValue,
      ).map(_.toOption.value)

      prices.map(_.currency.value) shouldBe List("EUR", "USD", "GBP")
      prices.map(_.amount.value) shouldBe List(BigDecimal("12.00"), BigDecimal("12.30"), BigDecimal("12.34"))
      prices.map(_.amount.value.scale) shouldBe List(2, 2, 2)
    }

    "accept zero, the largest permitted whole component, and a bounded negative-scale amount" in {
      val prices = List(
        validator.validate((amountRaw = BigDecimal(BigInt(0), -1_000_000), currency = Currency.EUR)).zioValue,
        validator.validate((amountRaw = BigDecimal("999999999999.99"), currency = Currency.USD)).zioValue,
        validator.validate((amountRaw = BigDecimal(BigInt(12), -1), currency = Currency.GBP)).zioValue,
      ).map(_.toOption.value)

      prices.map(_.amount.value) shouldBe
        List(BigDecimal("0.00"), BigDecimal("999999999999.99"), BigDecimal("120.00"))
      prices.map(_.amount.value.scale) shouldBe List(2, 2, 2)
    }

    "reject negative amounts" in {
      validator
        .validate((amountRaw = BigDecimal("-0.01"), currency = Currency.EUR))
        .zioValue
        .toEither
        .left
        .toOption
        .value
        .toNonEmptyList
        .toList shouldBe List(
        InvalidFieldError("amount", "Amount must be non-negative", List("-0.01"))
      )
    }

    "reject more than twelve integer digits before expanding a compact exponent" in {
      val amounts = List(BigDecimal("1000000000000"), BigDecimal(BigInt(1), -1_000_000))

      val errors = amounts.flatMap(amount =>
        validator
          .validate((amountRaw = amount, currency = Currency.USD))
          .zioValue
          .toEither
          .left
          .toOption
          .value
          .toNonEmptyList
          .toList
      )

      errors shouldBe amounts.map(amount =>
        InvalidFieldError("amount", "Amount must have at most [12] integer digits", List(amount.toString))
      )
    }

    "reject supplied scale above a currency's decimal-place limit, including trailing zeroes" in {
      val amounts = List(BigDecimal("1.230"), BigDecimal("0.000"))

      val errors = amounts.flatMap(amount =>
        validator
          .validate((amountRaw = amount, currency = Currency.GBP))
          .zioValue
          .toEither
          .left
          .toOption
          .value
          .toNonEmptyList
          .toList
      )

      errors shouldBe amounts.map(amount =>
        InvalidFieldError("amount", "Amount must have at most [2] decimal places", List(amount.toString))
      )
    }
  }
}
