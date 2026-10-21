package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.AddressValidator
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.{Arbitrary, Gen}
import zio.*

class AddressValidatorSpec extends ZWordSpecBase, GatewayArbitraries, IronRefinedTypeTransformer {

  private inline val addressPartLengthMax       = 255
  private inline val addressPartRawBlank        = ""
  private inline val addressEntriesCountTooMany = 3

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val addressValidator = ZIO
    .service[AddressValidator]
    .provide(AddressValidator.live)
    .zioValue

  private def validateAddressEntry(addressEntryRequestSmithy: smithy.AddressEntryRequest) =
    addressValidator.validateAddressEntries(List(addressEntryRequestSmithy)).map(_.map(_.head))

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
    "validateAddressEntries" should {
      "build an address entry from valid optional parts, keeping its address type" in {
        val addressFieldNames     = List("addressLine1", "addressLine2", "city", "postalCode", "country")
        val addressFieldsCountMin = 1
        val arbAddress            = Arbitrary(
          for {
            addressFieldsCount <- Gen.choose(addressFieldsCountMin, addressFieldNames.size)
            addressFields      <- Gen.pick(addressFieldsCount, addressFieldNames)
            addressLine1       <- Arbitrary.arbitrary[AddressLine1]
            addressLine2       <- Arbitrary.arbitrary[AddressLine2]
            city               <- Arbitrary.arbitrary[City]
            postalCode         <- Arbitrary.arbitrary[PostalCode]
            country            <- Arbitrary.arbitrary[Country]
          } yield Address(
            addressLine1 = Option.when(addressFields.contains("addressLine1"))(addressLine1),
            addressLine2 = Option.when(addressFields.contains("addressLine2"))(addressLine2),
            city = Option.when(addressFields.contains("city"))(city),
            postalCode = Option.when(addressFields.contains("postalCode"))(postalCode),
            country = Option.when(addressFields.contains("country"))(country),
          )
        )

        forAll(arbAddress.arbitrary) { address =>
          val addressEntry = AddressEntry(address = address, addressType = AddressType.ShippingAndBilling)

          validateAddressEntry(
            addressEntry.transformInto[smithy.AddressEntryRequest]
          ).zioValue.toEither.value shouldBe addressEntry
        }
      }

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

      "reject an address when every field is missing" in {
        validateAddressEntry(
          smithy.AddressEntryRequest(
            address = smithy.AddressRequest(),
            addressType = smithy.AddressType.ShippingAndBilling,
          )
        ).zioValue.toEither.left.value.toNonEmptyList.toList shouldBe List(addressAllPartsEmptyError)
      }

      "reject each supplied blank or whitespace-only field while allowing missing fields" in {
        val cityRawWhitespaceOnly       = " "
        val postalCodeRawWhitespaceOnly = "\t"
        val addressType                 = AddressType.ShippingAndBilling

        validateAddressEntry(
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
        ).zioValue.toEither.left.value.toNonEmptyList.toList shouldBe List(
          addressAllPartsEmptyError,
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("city", nonEmptyTrimmedError, List(cityRawWhitespaceOnly)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(postalCodeRawWhitespaceOnly)),
        )
      }

      "accumulate the address-level and field errors when every address part is empty" in {
        val addressType = AddressType.ShippingAndBilling

        validateAddressEntry(
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
        ).zioValue.toEither.left.value.toNonEmptyList.toList shouldBe List(
          addressAllPartsEmptyError,
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("city", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(addressPartRawBlank)),
          InvalidFieldError("country", nonEmptyTrimmedError, List(addressPartRawBlank)),
        )
      }

      "reject every untrimmed address part and report the original values" in {
        val addressLine1             = arbitrarySample[AddressLine1]
        val addressLine2             = arbitrarySample[AddressLine2]
        val city                     = arbitrarySample[City]
        val postalCode               = arbitrarySample[PostalCode]
        val country                  = arbitrarySample[Country]
        val addressLine1RawUntrimmed = s" ${addressLine1.value}"
        val addressLine2RawUntrimmed = s"${addressLine2.value} "
        val cityRawUntrimmed         = s"\t${city.value}"
        val postalCodeRawUntrimmed   = s"${postalCode.value}\n"
        val countryRawUntrimmed      = s" ${country.value} "
        val addressType              = AddressType.ShippingAndBilling

        validateAddressEntry(
          smithy.AddressEntryRequest(
            address = smithy.AddressRequest(
              addressLine1 = Some(addressLine1RawUntrimmed),
              addressLine2 = Some(addressLine2RawUntrimmed),
              city = Some(cityRawUntrimmed),
              postalCode = Some(postalCodeRawUntrimmed),
              country = Some(countryRawUntrimmed),
            ),
            addressType = addressType.transformInto[smithy.AddressType],
          )
        ).zioValue.toEither.left.value.toNonEmptyList.toList shouldBe List(
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, List(addressLine1RawUntrimmed)),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressLine2RawUntrimmed)),
          InvalidFieldError("city", nonEmptyTrimmedError, List(cityRawUntrimmed)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(postalCodeRawUntrimmed)),
          InvalidFieldError("country", nonEmptyTrimmedError, List(countryRawUntrimmed)),
        )
      }

      "fail with an InvalidFieldError for every address part longer than the maximum length, in field order" in {
        val addressType           = AddressType.ShippingAndBilling
        val addressPartRawTooLong = "a" * (addressPartLengthMax + 1)

        validateAddressEntry(
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
        ).zioValue.toEither.left.value.toNonEmptyList.toList shouldBe List(
          InvalidFieldError("addressLine1", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("city", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("postalCode", nonEmptyTrimmedError, List(addressPartRawTooLong)),
          InvalidFieldError("country", nonEmptyTrimmedError, List(addressPartRawTooLong)),
        )
      }

      "reject a supplied empty second address line" in {
        val addressLine1 = arbitrarySample[AddressLine1]
        val city         = arbitrarySample[City]
        val postalCode   = arbitrarySample[PostalCode]
        val country      = arbitrarySample[Country]
        val addressType  = AddressType.ShippingAndBilling

        validateAddressEntry(
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
        ).zioValue.toEither.left.value.toNonEmptyList.toList shouldBe List(
          InvalidFieldError("addressLine2", nonEmptyTrimmedError, List(addressPartRawBlank))
        )
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

      "fail with the combination error before validating an all-empty address" in {
        val addressEntryRequestSmithy =
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Shipping)
            .transformInto[smithy.AddressEntryRequest]

        addressValidator
          .validateAddressEntries(
            List(
              addressEntryRequestSmithy,
              addressEntryRequestSmithy.copy(
                address = smithy.AddressRequest(
                  addressLine1 = None,
                  addressLine2 = None,
                  city = None,
                  postalCode = None,
                  country = None,
                )
              ),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }

      "fail with the combination error before validating an invalid populated address" in {
        val cityRawTooLong            = "a" * (addressPartLengthMax + 1)
        val addressEntryRequestSmithy =
          AddressEntry(address = arbitrarySample[Address], addressType = AddressType.Shipping)
            .transformInto[smithy.AddressEntryRequest]

        addressValidator
          .validateAddressEntries(
            List(
              addressEntryRequestSmithy,
              addressEntryRequestSmithy.copy(
                address = addressEntryRequestSmithy.address.copy(city = Some(cityRawTooLong))
              ),
            )
          )
          .zioValue
          .toEither
          .left
          .value
          .toNonEmptyList
          .toList shouldBe List(addressEntriesCombinationError)
      }
    }
  }
}
