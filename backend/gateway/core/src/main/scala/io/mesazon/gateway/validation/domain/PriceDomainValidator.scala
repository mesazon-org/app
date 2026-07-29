package io.mesazon.gateway.validation.domain

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

import java.util.{Currency, Locale}

final class PriceDomainValidator extends DomainValidator[PriceDomainValidator.PriceRaw, Price] {

  override def validate(priceRaw: PriceDomainValidator.PriceRaw): UIO[ValidatedNec[InvalidFieldError, Price]] =
    ZIO.succeed {
      val (amountRaw, currencyRaw) = priceRaw
      val currencyFormatted = currencyRaw.trim.toUpperCase(Locale.ROOT)
      (
        validateAmount(amountRaw),
        validateCurrency(amountRaw, currencyRaw, currencyFormatted),
      ).mapN { (priceAmount, priceCurrencyWithFractionDigits) =>
        val priceAmountNormalized =
          PriceAmount(priceAmount.value.setScale(priceCurrencyWithFractionDigits.fractionDigits))
        Price(priceAmountNormalized, priceCurrencyWithFractionDigits.priceCurrency)
      }
    }

  private def validateAmount(amountRaw: BigDecimal): ValidatedNec[InvalidFieldError, PriceAmount] =
    if (amountRaw.signum < 0)
      InvalidFieldError("amount", "Amount must be non-negative", List(amountRaw.toString)).invalidNec
    else PriceAmount(amountRaw).validNec

  private def validateCurrency(
      amountRaw: BigDecimal,
      currencyRaw: String,
      currencyFormatted: String,
  ): ValidatedNec[InvalidFieldError, PriceCurrencyWithFractionDigits] =
    Either
      .catchOnly[IllegalArgumentException](Currency.getInstance(currencyFormatted))
      .leftMap(_ => InvalidFieldError("currency", s"Unsupported ISO currency: [$currencyRaw]", List(currencyRaw)))
      .toValidatedNec
      .andThen { currency =>
        val fractionDigits = currency.getDefaultFractionDigits
        if (fractionDigits < 0)
          InvalidFieldError(
            "currency",
            s"Currency [$currencyFormatted] does not have fixed fraction digits",
            List(currencyRaw),
          ).invalidNec
        else
          PriceCurrency.either(currencyFormatted).leftMap(errorMessage =>
            InvalidFieldError("currency", errorMessage, List(currencyRaw))
          ).map(PriceCurrencyWithFractionDigits(_, fractionDigits)).toValidatedNec
      }
      .andThen { priceCurrencyWithFractionDigits =>
        if (amountRaw.scale > priceCurrencyWithFractionDigits.fractionDigits)
          InvalidFieldError(
            "amount",
            s"Amount scale [${amountRaw.scale}] exceeds currency fraction digits [${priceCurrencyWithFractionDigits.fractionDigits}]",
            List(amountRaw.toString),
          ).invalidNec
        else priceCurrencyWithFractionDigits.validNec
      }

  private case class PriceCurrencyWithFractionDigits(
      priceCurrency: PriceCurrency,
      fractionDigits: Int,
  )
}

object PriceDomainValidator {

  type PriceRaw = (
      amountRaw: BigDecimal,
      currencyRaw: String,
  )

  val live = ZLayer.derive[PriceDomainValidator]
}
