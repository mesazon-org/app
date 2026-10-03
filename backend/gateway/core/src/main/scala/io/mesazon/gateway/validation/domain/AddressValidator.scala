package io.mesazon.gateway.validation.domain

import cats.data.{Validated, ValidatedNec}
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.service.{validateAll, validateOptionalField}
import io.scalaland.chimney.dsl.*
import zio.*

final class AddressValidator {

  private inline def trimAndFilterNonEmpty(part: Option[String]): Option[String] =
    part.map(_.trim).filter(_.nonEmpty)

  private inline def validateAddressHasAtLeastOneFieldNonEmpty(
      addressEntryRequestSmithy: smithy.AddressEntryRequest
  ): ValidatedNec[InvalidFieldError, Unit] =
    if (
      List(
        trimAndFilterNonEmpty(addressEntryRequestSmithy.address.addressLine1),
        trimAndFilterNonEmpty(addressEntryRequestSmithy.address.addressLine2),
        trimAndFilterNonEmpty(addressEntryRequestSmithy.address.city),
        trimAndFilterNonEmpty(addressEntryRequestSmithy.address.postalCode),
        trimAndFilterNonEmpty(addressEntryRequestSmithy.address.country),
      ).exists(_.nonEmpty)
    ) ().validNec
    else
      InvalidFieldError(
        "address",
        "At least one address field must be provided",
        Seq.empty,
      ).invalidNec

  private def validateAddress(
      addressEntryRequestSmithy: smithy.AddressEntryRequest
  ): ValidatedNec[InvalidFieldError, AddressEntry] =
    (
      (
        validateOptionalField(
          "addressLine1",
          addressEntryRequestSmithy.address.addressLine1,
          AddressLine1.either,
        ),
        validateOptionalField(
          "addressLine2",
          addressEntryRequestSmithy.address.addressLine2,
          AddressLine2.either,
        ),
        validateOptionalField("city", addressEntryRequestSmithy.address.city, City.either),
        validateOptionalField(
          "postalCode",
          addressEntryRequestSmithy.address.postalCode,
          PostalCode.either,
        ),
        validateOptionalField(
          "country",
          addressEntryRequestSmithy.address.country,
          Country.either,
        ),
      ).mapN(Address.apply),
      Validated.validNec(addressEntryRequestSmithy.addressType.transformInto[AddressType]),
    ).mapN(AddressEntry.apply)

  private inline def validateAddressTypesCombination(
      addressTypes: List[AddressType]
  ): ValidatedNec[InvalidFieldError, Unit] =
    addressTypes match {
      case Nil | List(AddressType.ShippingAndBilling) | List(AddressType.Shipping, AddressType.Billing) |
          List(AddressType.Billing, AddressType.Shipping) =>
        ().validNec
      case _ =>
        InvalidFieldError(
          "addresses",
          "Addresses must be empty, exactly one ShippingAndBilling entry, or exactly one Shipping and one Billing entry",
          Seq.empty,
        ).invalidNec
    }

  def validateAddressEntries(
      addressEntryRequests: List[smithy.AddressEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[AddressEntry]]] =
    validateAddressTypesCombination(addressEntryRequests.map(_.addressType).transformInto[List[AddressType]]) match {
      case invalid @ Validated.Invalid(_) => ZIO.succeed(invalid)
      case Validated.Valid(_)             =>
        validateAll(addressEntryRequests) { addressEntryRequestSmithy =>
          ZIO.succeed(
            validateAddressHasAtLeastOneFieldNonEmpty(addressEntryRequestSmithy) *> validateAddress(
              addressEntryRequestSmithy
            )
          )
        }
    }
}

object AddressValidator {

  val live = ZLayer.derive[AddressValidator]
}
