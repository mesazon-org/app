package io.mesazon.gateway.validation.domain

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

final class PriceValidator {

  def validate(priceRaw: PriceValidator.PriceRaw): UIO[ValidatedNec[InvalidFieldError, Price]] =
    ZIO.succeed {
      val (amountRaw, currency) = priceRaw
      validateAmount(amountRaw, currency).map(priceAmount => Price(priceAmount, currency))
    }

  private def validateAmount(
      amountRaw: BigDecimal,
      currency: Currency,
  ): ValidatedNec[InvalidFieldError, PriceAmount] =
    if (amountRaw.signum < 0)
      InvalidFieldError("amount", "Amount must be non-negative", List(amountRaw.toString)).invalidNec
    else if (
      amountRaw.signum != 0 &&
      amountRaw.precision.toLong - amountRaw.scale.toLong > PriceValidator.MaximumIntegerDigits
    )
      InvalidFieldError(
        "amount",
        s"Amount must have at most [${PriceValidator.MaximumIntegerDigits}] integer digits",
        List(amountRaw.toString),
      ).invalidNec
    else
      currency.decimalPoints match {
        case Some(decimalPoints) if amountRaw.scale > decimalPoints =>
          InvalidFieldError(
            "amount",
            s"Amount must have at most [$decimalPoints] decimal places",
            List(amountRaw.toString),
          ).invalidNec
        case Some(decimalPoints) => PriceAmount(amountRaw.setScale(decimalPoints)).validNec
        case None                => PriceAmount(amountRaw).validNec
      }
}

object PriceValidator {

  private val MaximumIntegerDigits = 12

  type PriceRaw = (
      amountRaw: BigDecimal,
      currency: Currency,
  )

  val live = ZLayer.derive[PriceValidator]
}
