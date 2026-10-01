package io.mesazon.gateway.validation.domain

import cats.data.{Validated, ValidatedNec}
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.service.addressTypeFromSmithyToDomain
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.service.{validateOptionalField, validateRequiredField}
import zio.{UIO, ZIO, ZLayer}

import scala.annotation.unused

final class AddressValidator {

  def validate(addressEntryRequest: smithy.AddressEntryRequest): UIO[ValidatedNec[InvalidFieldError, AddressEntry]] =
    ZIO.succeed(
      (
        (
          validateRequiredField("addressLine1", addressEntryRequest.address.addressLine1, AddressLine1.either),
          validateOptionalField("addressLine2", addressEntryRequest.address.addressLine2, AddressLine2.either),
          validateRequiredField("city", addressEntryRequest.address.city, City.either),
          validateRequiredField("postalCode", addressEntryRequest.address.postalCode, PostalCode.either),
          validateRequiredField("country", addressEntryRequest.address.country, Country.either),
        ).mapN(Address.apply),
        Validated.validNec(addressTypeFromSmithyToDomain(addressEntryRequest.addressType)),
      ).mapN(AddressEntry.apply)
    )

  def validateAddressEntries(
      @unused addressEntryRequests: List[smithy.AddressEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[AddressEntry]]] =
    ZIO.die(new NotImplementedError("AddressValidator.validateAddressEntries is not implemented yet"))
}

object AddressValidator {

  val live = ZLayer.derive[AddressValidator]
}
