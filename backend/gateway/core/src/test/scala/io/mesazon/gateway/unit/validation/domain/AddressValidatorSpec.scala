package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
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
      "build a customer address entry from valid parts, keeping its address type" in {
        val addressEntry = arbitrarySample[AddressEntry].copy(addressLine2 = Some(arbitrarySample[AddressLine2]))

        addressValidator
          .validate(
            addressLine1Raw = addressEntry.addressLine1.value,
            addressLine2RawOpt = addressEntry.addressLine2.map(_.value),
            cityRaw = addressEntry.city.value,
            postalCodeRaw = addressEntry.postalCode.value,
            countryRaw = addressEntry.country.value,
            addressType = addressEntry.addressType,
            addressEntryConstructor = CustomerAddressEntry.either,
          )
          .zioValue
          .toEither
          .value shouldBe CustomerAddressEntry(addressEntry)
      }

      "build a customer address entry without a second address line when none is given" in {
        val addressEntry = arbitrarySample[AddressEntry].copy(addressLine2 = None)

        addressValidator
          .validate(
            addressLine1Raw = addressEntry.addressLine1.value,
            addressLine2RawOpt = None,
            cityRaw = addressEntry.city.value,
            postalCodeRaw = addressEntry.postalCode.value,
            countryRaw = addressEntry.country.value,
            addressType = addressEntry.addressType,
            addressEntryConstructor = CustomerAddressEntry.either,
          )
          .zioValue
          .toEither
          .value shouldBe CustomerAddressEntry(addressEntry)
      }

      "build an organization address entry from valid parts" in {
        val addressEntry = arbitrarySample[AddressEntry]

        addressValidator
          .validate(
            addressLine1Raw = addressEntry.addressLine1.value,
            addressLine2RawOpt = addressEntry.addressLine2.map(_.value),
            cityRaw = addressEntry.city.value,
            postalCodeRaw = addressEntry.postalCode.value,
            countryRaw = addressEntry.country.value,
            addressType = addressEntry.addressType,
            addressEntryConstructor = OrganizationAddressEntry.either,
          )
          .zioValue
          .toEither
          .value shouldBe OrganizationAddressEntry(addressEntry)
      }

      "fail with an InvalidFieldError for every blank address part, in field order" in {
        val addressType = arbitrarySample[AddressType]

        addressValidator
          .validate(
            addressLine1Raw = addressPartRawBlank,
            addressLine2RawOpt = Some(addressPartRawBlank),
            cityRaw = addressPartRawBlank,
            postalCodeRaw = addressPartRawBlank,
            countryRaw = addressPartRawBlank,
            addressType = addressType,
            addressEntryConstructor = CustomerAddressEntry.either,
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
        val addressEntry = arbitrarySample[AddressEntry].copy(addressLine2 = Some(arbitrarySample[AddressLine2]))
        val addressLine1RawUntrimmed    = s" ${addressEntry.addressLine1.value}"
        val addressLine2RawUntrimmedOpt = addressEntry.addressLine2.map(addressLine2 => s"${addressLine2.value} ")
        val cityRawUntrimmed            = s"\t${addressEntry.city.value}"
        val postalCodeRawUntrimmed      = s"${addressEntry.postalCode.value}\n"
        val countryRawUntrimmed         = s" ${addressEntry.country.value} "

        addressValidator
          .validate(
            addressLine1Raw = addressLine1RawUntrimmed,
            addressLine2RawOpt = addressLine2RawUntrimmedOpt,
            cityRaw = cityRawUntrimmed,
            postalCodeRaw = postalCodeRawUntrimmed,
            countryRaw = countryRawUntrimmed,
            addressType = addressEntry.addressType,
            addressEntryConstructor = CustomerAddressEntry.either,
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
            addressLine1Raw = addressPartRawTooLong,
            addressLine2RawOpt = Some(addressPartRawTooLong),
            cityRaw = addressPartRawTooLong,
            postalCodeRaw = addressPartRawTooLong,
            countryRaw = addressPartRawTooLong,
            addressType = addressType,
            addressEntryConstructor = CustomerAddressEntry.either,
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
            addressLine1Raw = addressEntry.addressLine1.value,
            addressLine2RawOpt = Some(addressPartRawBlank),
            cityRaw = addressEntry.city.value,
            postalCodeRaw = addressEntry.postalCode.value,
            countryRaw = addressEntry.country.value,
            addressType = addressEntry.addressType,
            addressEntryConstructor = CustomerAddressEntry.either,
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
