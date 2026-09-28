package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.service.addressTypeFromDomainToSmithy
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.AddressValidator
import io.mesazon.testkit.base.*
import zio.*

class AddressValidatorSpec extends ZWordSpecBase, GatewayArbitraries {

  inline private val addressPartLengthMax = 255
  inline private val addressPartRawBlank  = ""

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val addressValidator = ZIO
    .service[AddressValidator]
    .provide(AddressValidator.live)
    .zioValue

  "AddressValidator" when {
    "validate" should {
      "build an address entry from valid parts, keeping its address type" in {
        val addressEntry = AddressEntry(
          address = arbitrarySample[Address].copy(addressLine2 = Some(arbitrarySample[AddressLine2])),
          addressType = arbitrarySample[AddressType],
        )

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = addressEntry.address.addressLine1.value,
                addressLine2 = addressEntry.address.addressLine2.map(_.value),
                city = addressEntry.address.city.value,
                postalCode = addressEntry.address.postalCode.value,
                country = addressEntry.address.country.value,
              ),
              addressType = addressTypeFromDomainToSmithy(addressEntry.addressType),
            )
          )
          .zioValue
          .toEither
          .value shouldBe addressEntry
      }

      "build an address entry without a second address line when none is given" in {
        val addressEntry = AddressEntry(
          address = arbitrarySample[Address].copy(addressLine2 = None),
          addressType = arbitrarySample[AddressType],
        )

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = addressEntry.address.addressLine1.value,
                addressLine2 = addressEntry.address.addressLine2.map(_.value),
                city = addressEntry.address.city.value,
                postalCode = addressEntry.address.postalCode.value,
                country = addressEntry.address.country.value,
              ),
              addressType = addressTypeFromDomainToSmithy(addressEntry.addressType),
            )
          )
          .zioValue
          .toEither
          .value shouldBe addressEntry
      }

      "fail with an InvalidFieldError for every blank address part, in field order" in {
        val addressType = arbitrarySample[AddressType]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = addressPartRawBlank,
                addressLine2 = Some(addressPartRawBlank),
                city = addressPartRawBlank,
                postalCode = addressPartRawBlank,
                country = addressPartRawBlank,
              ),
              addressType = addressTypeFromDomainToSmithy(addressType),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("city", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("country", nonEmptyTrimmedError, List(addressPartRawBlank)),
        )
      }

      "fail with an InvalidFieldError for every address part with leading or trailing whitespace, in field order" in {
        val addressEntry = AddressEntry(
          address = arbitrarySample[Address].copy(addressLine2 = Some(arbitrarySample[AddressLine2])),
          addressType = arbitrarySample[AddressType],
        )
        val addressLine1RawUntrimmed    = s" ${addressEntry.address.addressLine1.value}"
        val addressLine2RawUntrimmedOpt =
          addressEntry.address.addressLine2.map(addressLine2 => s"${addressLine2.value} ")
        val cityRawUntrimmed       = s"\t${addressEntry.address.city.value}"
        val postalCodeRawUntrimmed = s"${addressEntry.address.postalCode.value}\n"
        val countryRawUntrimmed    = s" ${addressEntry.address.country.value} "

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = addressLine1RawUntrimmed,
                addressLine2 = addressLine2RawUntrimmedOpt,
                city = cityRawUntrimmed,
                postalCode = postalCodeRawUntrimmed,
                country = countryRawUntrimmed,
              ),
              addressType = addressTypeFromDomainToSmithy(addressEntry.addressType),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, List(addressLine1RawUntrimmed)),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, addressLine2RawUntrimmedOpt.toList),
          InvalidFieldError("city", nonEmptyTrimmedError, List(cityRawUntrimmed)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(postalCodeRawUntrimmed)),
          InvalidFieldError("country", nonEmptyTrimmedError, List(countryRawUntrimmed)),
        )
      }

      "fail with an InvalidFieldError for every address part longer than the maximum length, in field order" in {
        val addressType           = arbitrarySample[AddressType]
        val addressPartRawTooLong = "a" * (addressPartLengthMax + 1)

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = addressPartRawTooLong,
                addressLine2 = Some(addressPartRawTooLong),
                city = addressPartRawTooLong,
                postalCode = addressPartRawTooLong,
                country = addressPartRawTooLong,
              ),
              addressType = addressTypeFromDomainToSmithy(addressType),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("city", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("country", nonEmptyTrimmedError, List(addressPartRawTooLong)),
        )
      }

      "fail with an InvalidFieldError for only a blank second address line when every other part is valid" in {
        val addressEntry = arbitrarySample[AddressEntry]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = addressEntry.address.addressLine1.value,
                addressLine2 = Some(addressPartRawBlank),
                city = addressEntry.address.city.value,
                postalCode = addressEntry.address.postalCode.value,
                country = addressEntry.address.country.value,
              ),
              addressType = addressTypeFromDomainToSmithy(addressEntry.addressType),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawBlank))
        )
      }
    }
  }
}
