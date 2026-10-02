package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.AddressValidator
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.*

class AddressValidatorSpec extends ZWordSpecBase, GatewayArbitraries, IronRefinedTypeTransformer {

  inline private val addressPartLengthMax       = 255
  inline private val addressPartRawBlank        = ""
  inline private val addressEntriesCountTooMany = 3
  inline private val addressEntryIndexSecond    = 1

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val addressValidator = ZIO
    .service[AddressValidator]
    .provide(AddressValidator.live)
    .zioValue

  private val addressEntriesCombinationError = InvalidFieldError(
    "addresses",
    "Addresses must be empty, exactly one ShippingAndBilling entry, or exactly one Shipping and one Billing entry",
    Seq.empty,
  )

  private val addressAllPartsEmptyError = InvalidFieldError(
    "address",
    "At least one address field must be provided",
    Seq.empty,
  )

  "AddressValidator" when {
    "validate" should {
      "build an address entry from valid parts, keeping its address type" in {
        val addressEntry = AddressEntry(
          address = arbitrarySample[Address].copy(addressLine2 = Some(arbitrarySample[AddressLine2])),
          addressType = arbitrarySample[AddressType],
        )

        addressValidator
          .validate(
            addressEntry.transformInto[smithy.AddressEntryRequest]
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
            addressEntry.transformInto[smithy.AddressEntryRequest]
          )
          .zioValue
          .toEither
          .value shouldBe addressEntry
      }

      "build a partial address entry when only the city is given" in {
        val city        = arbitrarySample[City]
        val addressType = arbitrarySample[AddressType]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(city.value),
                postalCode = None,
                country = None,
              ),
              addressType = addressType.transformInto[smithy.AddressType],
            )
          )
          .zioValue
          .toEither
          .value shouldBe AddressEntry(
          address = Address(
            addressLine1 = None,
            addressLine2 = None,
            city = Some(city),
            postalCode = None,
            country = None,
          ),
          addressType = addressType,
        )
      }

      "trim populated address parts and discard blank address parts" in {
        val addressLine1             = arbitrarySample[AddressLine1]
        val city                     = arbitrarySample[City]
        val addressLine1RawUntrimmed = s"  ${addressLine1.value} "
        val addressLine2RawBlank     = " "
        val cityRawUntrimmed         = s"\t${city.value}\n"
        val postalCodeRawBlank       = "\t"
        val addressType              = arbitrarySample[AddressType]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = Some(addressLine1RawUntrimmed),
                addressLine2 = Some(addressLine2RawBlank),
                city = Some(cityRawUntrimmed),
                postalCode = Some(postalCodeRawBlank),
                country = None,
              ),
              addressType = addressType.transformInto[smithy.AddressType],
            )
          )
          .zioValue
          .toEither
          .value shouldBe AddressEntry(
          address = Address(
            addressLine1 = Some(addressLine1),
            addressLine2 = None,
            city = Some(city),
            postalCode = None,
            country = None,
          ),
          addressType = addressType,
        )
      }

      "fail with an InvalidFieldError when every address part is missing, empty, or whitespace only" in {
        val cityRawWhitespaceOnly       = " "
        val postalCodeRawWhitespaceOnly = "\t"
        val addressType                 = arbitrarySample[AddressType]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = None,
                addressLine2 = Some(addressPartRawBlank),
                city = Some(cityRawWhitespaceOnly),
                postalCode = Some(postalCodeRawWhitespaceOnly),
                country = None,
              ),
              addressType = addressType.transformInto[smithy.AddressType],
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressAllPartsEmptyError)
      }

      "fail with an InvalidFieldError for every blank address part, in field order" in {
        val addressType = arbitrarySample[AddressType]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = Some(addressPartRawBlank),
                addressLine2 = Some(addressPartRawBlank),
                city = Some(addressPartRawBlank),
                postalCode = Some(addressPartRawBlank),
                country = Some(addressPartRawBlank),
              ),
              addressType = addressType.transformInto[smithy.AddressType],
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
        val addressLine1RawUntrimmed    = Some(s" ${arbitrarySample[AddressLine1].value}")
        val addressLine2RawUntrimmedOpt = Some(s"${arbitrarySample[AddressLine2].value} ")
        val cityRawUntrimmed            = Some(s"\t${arbitrarySample[City].value}")
        val postalCodeRawUntrimmed      = Some(s"${arbitrarySample[PostalCode].value}\n")
        val countryRawUntrimmed         = Some(s" ${arbitrarySample[Country].value} ")
        val addressType                 = arbitrarySample[AddressType]

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
              addressType = addressType.transformInto[smithy.AddressType],
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, addressLine1RawUntrimmed.toList),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, addressLine2RawUntrimmedOpt.toList),
          InvalidFieldError("city", nonEmptyTrimmedError, cityRawUntrimmed.toList),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, postalCodeRawUntrimmed.toList),
          InvalidFieldError("country", nonEmptyTrimmedError, countryRawUntrimmed.toList),
        )
      }

      "fail with an InvalidFieldError for every address part longer than the maximum length, in field order" in {
        val addressType           = arbitrarySample[AddressType]
        val addressPartRawTooLong = "a" * (addressPartLengthMax + 1)

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = Some(addressPartRawTooLong),
                addressLine2 = Some(addressPartRawTooLong),
                city = Some(addressPartRawTooLong),
                postalCode = Some(addressPartRawTooLong),
                country = Some(addressPartRawTooLong),
              ),
              addressType = addressType.transformInto[smithy.AddressType],
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
        val addressLine1 = arbitrarySample[AddressLine1]
        val city         = arbitrarySample[City]
        val postalCode   = arbitrarySample[PostalCode]
        val country      = arbitrarySample[Country]
        val addressType  = arbitrarySample[AddressType]

        addressValidator
          .validate(
            smithy.AddressEntryRequest(
              address = smithy.AddressRequest(
                addressLine1 = Some(addressLine1.value),
                addressLine2 = Some(addressPartRawBlank),
                city = Some(city.value),
                postalCode = Some(postalCode.value),
                country = Some(country.value),
              ),
              addressType = addressType.transformInto[smithy.AddressType],
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

    "validateAddressEntries" should {
      "successfully validate an empty address list" in {
        val addressEntries = List.empty[AddressEntry]

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .value shouldBe addressEntries
      }

      "successfully validate a single address used for both shipping and billing" in {
        val addressEntries =
          List(AddressEntry(address = arbitrarySample[Address], addressType = AddressType.ShippingAndBilling))

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .value shouldBe addressEntries
      }

      "successfully validate a shipping address followed by a billing address" in {
        val addressEntries = List(
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Shipping),
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Billing),
        )

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .value shouldBe addressEntries
      }

      "successfully validate a billing address followed by a shipping address" in {
        val addressEntries = List(
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Billing),
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Shipping),
        )

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .value shouldBe addressEntries
      }

      "fail with an InvalidFieldError when more than two addresses are given" in {
        val addressEntries = arbitrarySample[AddressEntry](addressEntriesCountTooMany).toList

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }

      "fail with an InvalidFieldError when two addresses share the same type" in {
        val addressType    = arbitrarySample[AddressType]
        val addressEntries = List(
          AddressEntry(address = arbitrarySample[Address], addressType = addressType),
          AddressEntry(address = arbitrarySample[Address], addressType = addressType),
        )

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }

      "fail with an InvalidFieldError when a single address is shipping only" in {
        val addressEntries = List(AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Shipping))

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }

      "fail with an InvalidFieldError when a single address is billing only" in {
        val addressEntries = List(AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Billing))

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }

      "fail with an InvalidFieldError when a shipping and billing address comes with another address" in {
        val addressTypeOther = Random.shuffle(List(AddressType.Shipping, AddressType.Billing)).zioValue.head
        val addressEntries   = List(
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.ShippingAndBilling),
          AddressEntry(address = arbitrarySample[Address], addressType = addressTypeOther),
        )

        addressValidator
          .validateAddressEntries(addressEntries.transformInto[List[smithy.AddressEntryRequest]])
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }

      "fail with InvalidFieldErrors for an invalid address part and the combination together, part errors first" in {
        val addressEntryRequestSmithy =
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Shipping)
            .transformInto[smithy.AddressEntryRequest]

        addressValidator
          .validateAddressEntries(
            List(
              addressEntryRequestSmithy,
              addressEntryRequestSmithy.copy(address =
                addressEntryRequestSmithy.address.copy(city = Some(addressPartRawBlank))
              ),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(
          InvalidFieldError("city", nonEmptyTrimmedError, List(addressPartRawBlank), index = addressEntryIndexSecond),
          addressEntriesCombinationError,
        )
      }
    }
  }
}
