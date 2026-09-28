package io.mesazon.gateway.validation.domain

import cats.data.{Validated, ValidatedNec}
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.service.addressTypeFromSmithyToDomain
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.service.{validateOptionalField, validateRequiredField}
import zio.{UIO, ZIO, ZLayer}

final class AddressValidator {

  def validate(
      addressLine1Raw: String,
      addressLine2RawOpt: Option[String],
      cityRaw: String,
      postalCodeRaw: String,
      countryRaw: String,
      addressTypeRaw: smithy.AddressType,
  ): UIO[ValidatedNec[InvalidFieldError, AddressEntry]] =
    ZIO.succeed(
      (
        (
          validateRequiredField("addressLine1", addressLine1Raw, AddressLine1.either),
          validateOptionalField("addressLine2", addressLine2RawOpt, AddressLine2.either),
          validateRequiredField("city", cityRaw, City.either),
          validateRequiredField("postalCode", postalCodeRaw, PostalCode.either),
          validateRequiredField("country", countryRaw, Country.either),
        ).mapN(Address.apply),
        Validated.validNec(addressTypeFromSmithyToDomain(addressTypeRaw)),
      ).mapN(AddressEntry.apply)
    )
}

object AddressValidator {

  val live = ZLayer.derive[AddressValidator]
}
