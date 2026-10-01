package io.mesazon.gateway.validation.domain

import cats.data.{Validated, ValidatedNec}
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.service.addressTypeFromSmithyToDomain
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.service.{validateAll, validateOptionalField, validateRequiredField}
import zio.{UIO, ZIO, ZLayer}

final class AddressValidator {

  private def validateAddressTypesCombination(addressTypes: List[AddressType]): ValidatedNec[InvalidFieldError, Unit] =
    addressTypes match {
      case Nil | List(AddressType.ShippingAndBilling) | List(AddressType.Shipping, AddressType.Billing) |
          List(AddressType.Billing, AddressType.Shipping) =>
        ().validNec
      case _ =>
        InvalidFieldError(
          "addresses",
          "Addresses must be empty, exactly one SHIPPING_AND_BILLING entry, or exactly one SHIPPING and one BILLING entry",
          Seq.empty,
        ).invalidNec
    }

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
      addressEntryRequests: List[smithy.AddressEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[AddressEntry]]] =
    validateAll(addressEntryRequests)(validate).map(
      _ <* validateAddressTypesCombination(
        addressEntryRequests.map(addressEntryRequest => addressTypeFromSmithyToDomain(addressEntryRequest.addressType))
      )
    )
}

object AddressValidator {

  val live = ZLayer.derive[AddressValidator]
}
