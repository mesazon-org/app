package io.mesazon.gateway.unit.validation.service

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import smithy4s.Nullable
import zio.*

class CustomerBookRequestValidatorSpec extends ZWordSpecBase, CustomerBookSmithyArbitraries {

  private inline val NonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val customerBookRequestValidator: CustomerBookRequestValidator = ZIO
    .service[CustomerBookRequestValidator]
    .provide(
      CustomerBookRequestValidator.live,
      EmailValidator.live,
      AddressValidator.live,
      PhoneNumberDomainValidator.live,
      PhoneNumberUtil.live,
      ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
    )
    .zioValue

  private def emailFormatError(emailRaw: String, index: Int = 0) =
    InvalidFieldError("email", s"Invalid email format: [$emailRaw], error: [null]", List(emailRaw), index = index)

  private def validCustomerEmailEntrySmithy(index: Int, isDefault: Boolean) =
    smithy.CustomerEmailEntry(email = s"user$index@example.com", isDefault = isDefault)

  "CustomerBookRequestValidator" should {

    "validatedCustomerBookIndividualPostRequest" should {
      "successfully validate a valid individual" in {
        val customerBookIndividualPostRequest =
          arbitrarySample[CustomerBookIndividualPostRequest]

        customerBookRequestValidator
          .validatedCustomerBookIndividualPostRequest(
            customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest]
          )
          .zioValue shouldBe customerBookIndividualPostRequest
      }

      "fail with a ValidationError accumulating every field error in request order" in {
        val customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
          .transformInto[smithy.CustomerBookIndividualPostRequest]
          .copy(
            fullName = "",
            emails = List(smithy.CustomerEmailEntry(email = "invalid-email", isDefault = true)),
            phoneNumbers = List(
              smithy.CustomerPhoneNumberEntry(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNationalNumber = "77 347 7752 ",
                  phoneCountryCode = " +4 4 ",
                ),
                isDefault = true,
              )
            ),
            addresses = List(
              smithy.CustomerAddressEntry(
                address = smithy.AddressRequest(
                  addressLine1 = Some("1 Main Street"),
                  city = Some(""),
                  postalCode = Some("1010"),
                  country = Some("Cyprus"),
                ),
                addressType = smithy.AddressType.ShippingAndBilling,
              )
            ),
          )

        customerBookRequestValidator
          .validatedCustomerBookIndividualPostRequest(customerBookIndividualPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", NonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
              InvalidFieldError(
                "phoneNationalNumber",
                "PhoneNationalNumber could not be validated using phoneNumberUtil, phoneCountryCode: [ +4 4 ], phoneNationalNumber: [77 347 7752 ], supportedPhoneRegions: [CY,GB], error: [None]",
                List("77 347 7752 "),
                index = 0,
              ),
              InvalidFieldError("city", NonEmptyTrimmedError, List(""), index = 0),
            )
          )
      }

      "fail with a ValidationError when contact point lists do not have exactly one default" in {
        val customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
          .transformInto[smithy.CustomerBookIndividualPostRequest]
          .copy(
            emails = List(
              validCustomerEmailEntrySmithy(0, isDefault = true),
              validCustomerEmailEntrySmithy(1, isDefault = true),
            ),
            phoneNumbers = List(
              smithy.CustomerPhoneNumberEntry(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNationalNumber = "99135215",
                  phoneCountryCode = "+357",
                ),
                isDefault = false,
              )
            ),
          )

        customerBookRequestValidator
          .validatedCustomerBookIndividualPostRequest(customerBookIndividualPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("emails", "Exactly one entry must be marked as default", List()),
              InvalidFieldError("phoneNumbers", "Exactly one entry must be marked as default", List()),
            )
          )
      }
    }

    "validatedCustomerBookBusinessPostRequest" should {
      "successfully validate a valid business" in {
        val customerBookBusinessPostRequest =
          arbitrarySample[CustomerBookBusinessPostRequest]

        customerBookRequestValidator
          .validatedCustomerBookBusinessPostRequest(
            customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest]
          )
          .zioValue shouldBe customerBookBusinessPostRequest
      }

      "fail with a ValidationError accumulating every field and contact error in request order" in {
        val validCustomerBusinessContactInsertSmithy = arbitrarySample[CustomerBusinessContactInsert]
          .transformInto[smithy.CustomerBusinessContactInsert]
        val invalidPhoneNumber = smithy.PhoneNumberRequest(
          phoneNationalNumber = "77 347 7752 ",
          phoneCountryCode = " +4 4 ",
        )
        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]
          .copy(
            businessName = "",
            emails = List(smithy.CustomerEmailEntry(email = "invalid-email", isDefault = true)),
            taxID = Some(""),
            phoneNumbers = List(
              smithy.CustomerPhoneNumberEntry(
                phoneNumber = invalidPhoneNumber,
                isDefault = true,
              )
            ),
            addresses = List(
              smithy.CustomerAddressEntry(
                address = smithy.AddressRequest(
                  addressLine1 = Some("1 Main Street"),
                  city = Some(""),
                  postalCode = Some("1010"),
                  country = Some("Cyprus"),
                ),
                addressType = smithy.AddressType.ShippingAndBilling,
              )
            ),
            customerBusinessContacts = List(
              validCustomerBusinessContactInsertSmithy,
              smithy.CustomerBusinessContactInsert(
                fullName = "",
                role = Some(""),
                email = Some("invalid-contact-email"),
                phoneNumber = Some(invalidPhoneNumber),
              ),
            ),
          )

        customerBookRequestValidator
          .validatedCustomerBookBusinessPostRequest(customerBookBusinessPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("businessName", NonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
              InvalidFieldError("taxID", NonEmptyTrimmedError, List("")),
              InvalidFieldError(
                "phoneNationalNumber",
                "PhoneNationalNumber could not be validated using phoneNumberUtil, phoneCountryCode: [ +4 4 ], phoneNationalNumber: [77 347 7752 ], supportedPhoneRegions: [CY,GB], error: [None]",
                List("77 347 7752 "),
                index = 0,
              ),
              InvalidFieldError("city", NonEmptyTrimmedError, List(""), index = 0),
              InvalidFieldError("fullName", NonEmptyTrimmedError, List(""), index = 1),
              InvalidFieldError("role", NonEmptyTrimmedError, List(""), index = 1),
              emailFormatError("invalid-contact-email", index = 1),
              InvalidFieldError(
                "phoneNationalNumber",
                "PhoneNationalNumber could not be validated using phoneNumberUtil, phoneCountryCode: [ +4 4 ], phoneNationalNumber: [77 347 7752 ], supportedPhoneRegions: [CY,GB], error: [None]",
                List("77 347 7752 "),
                index = 1,
              ),
            )
          )
      }

      "fail with a ValidationError when contact point lists do not have exactly one default" in {
        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]
          .copy(
            emails = List(
              validCustomerEmailEntrySmithy(0, isDefault = true),
              validCustomerEmailEntrySmithy(1, isDefault = true),
            ),
            phoneNumbers = List(
              smithy.CustomerPhoneNumberEntry(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNationalNumber = "99135215",
                  phoneCountryCode = "+357",
                ),
                isDefault = false,
              )
            ),
          )

        customerBookRequestValidator
          .validatedCustomerBookBusinessPostRequest(customerBookBusinessPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("emails", "Exactly one entry must be marked as default", List()),
              InvalidFieldError("phoneNumbers", "Exactly one entry must be marked as default", List()),
            )
          )
      }
    }

    "validatedCustomerBookBatchPostRequest" should {
      "successfully validate a mixed batch" in {
        val customerBookBatchPostRequest =
          arbitrarySample[CustomerBookBatchPostRequest]

        customerBookRequestValidator
          .validatedCustomerBookBatchPostRequest(
            customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]
          )
          .zioValue shouldBe customerBookBatchPostRequest
      }

      "fail with a ValidationError preserving business and individual indexes around valid entries" in {
        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]
        val customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
          .transformInto[smithy.CustomerBookIndividualPostRequest]
        val customerBookBatchPostRequestSmithy = smithy.CustomerBookBatchPostRequest(
          customerBusinesses = List(
            customerBookBusinessPostRequestSmithy.copy(businessName = ""),
            customerBookBusinessPostRequestSmithy,
            customerBookBusinessPostRequestSmithy.copy(emails =
              List(
                validCustomerEmailEntrySmithy(0, isDefault = true),
                smithy.CustomerEmailEntry(email = "bad-business-email", isDefault = false),
              )
            ),
          ),
          customerIndividuals = List(
            customerBookIndividualPostRequestSmithy.copy(fullName = ""),
            customerBookIndividualPostRequestSmithy,
            customerBookIndividualPostRequestSmithy.copy(emails =
              List(
                validCustomerEmailEntrySmithy(0, isDefault = true),
                smithy.CustomerEmailEntry(email = "bad-individual-email", isDefault = false),
              )
            ),
          ),
        )

        val businessNameError    = InvalidFieldError("businessName", NonEmptyTrimmedError, List(""))
        val businessEmailError   = emailFormatError("bad-business-email", index = 1)
        val fullNameError        = InvalidFieldError("fullName", NonEmptyTrimmedError, List(""))
        val individualEmailError = emailFormatError("bad-individual-email", index = 1)

        customerBookRequestValidator
          .validatedCustomerBookBatchPostRequest(customerBookBatchPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError(
                "customerBusiness",
                s"Failed with invalid fields [$businessNameError]",
                List(),
                index = 0,
              ),
              InvalidFieldError(
                "customerBusiness",
                s"Failed with invalid fields [$businessEmailError]",
                List(),
                index = 2,
              ),
              InvalidFieldError(
                "customerIndividual",
                s"Failed with invalid fields [$fullNameError]",
                List(),
                index = 0,
              ),
              InvalidFieldError(
                "customerIndividual",
                s"Failed with invalid fields [$individualEmailError]",
                List(),
                index = 2,
              ),
            )
          )
      }
    }

    "validatedCustomerBookIndividualPutRequest" should {
      "preserve omitted fields as keep operations" in {
        customerBookRequestValidator
          .validatedCustomerBookIndividualPutRequest(smithy.CustomerBookIndividualPutRequest())
          .zioValue shouldBe CustomerBookIndividualPutRequest(
          fullName = None,
          emails = None,
          phoneNumbers = None,
          addresses = None,
        )
      }

      "preserve present empty contact point arrays as clear operations" in {
        val customerBookIndividualPutRequest = smithy.CustomerBookIndividualPutRequest(
          emails = Some(List.empty),
          phoneNumbers = Some(List.empty),
          addresses = Some(List.empty),
        )

        customerBookRequestValidator
          .validatedCustomerBookIndividualPutRequest(customerBookIndividualPutRequest)
          .zioValue shouldBe
          CustomerBookIndividualPutRequest(
            fullName = None,
            emails = Some(List.empty),
            phoneNumbers = Some(List.empty),
            addresses = Some(List.empty),
          )
      }

      "validate present populated contact point arrays as replacement values" in {
        val customerBookIndividualPutRequest = arbitrarySample[CustomerBookIndividualPutRequest].copy(
          emails = Some(List(arbitrarySample[CustomerEmailEntry].copy(isDefault = true))),
          phoneNumbers = Some(List(arbitrarySample[CustomerPhoneNumberEntry].copy(isDefault = true))),
          addresses = Some(
            List(
              CustomerAddressEntry(
                AddressEntry(
                  address = Address(Some(AddressLine1.assume("1 Main Street")), None, None, None, None),
                  addressType = AddressType.ShippingAndBilling,
                )
              )
            )
          ),
        )

        customerBookRequestValidator
          .validatedCustomerBookIndividualPutRequest(
            customerBookIndividualPutRequest.transformInto[smithy.CustomerBookIndividualPutRequest]
          )
          .zioValue shouldBe customerBookIndividualPutRequest
      }

      "fail with a ValidationError accumulating every field error in request order" in {
        val customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
          fullName = Some(""),
          emails = Some(List(smithy.CustomerEmailEntry(email = "invalid-email", isDefault = true))),
          phoneNumbers = Some(
            List(
              smithy.CustomerPhoneNumberEntry(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNationalNumber = "77 347 7752 ",
                  phoneCountryCode = " +4 4 ",
                ),
                isDefault = true,
              )
            )
          ),
          addresses = Some(
            List(
              smithy.CustomerAddressEntry(
                address = smithy.AddressRequest(
                  addressLine1 = Some("1 Main Street"),
                  city = Some(""),
                  postalCode = Some("1010"),
                  country = Some("Cyprus"),
                ),
                addressType = smithy.AddressType.ShippingAndBilling,
              )
            )
          ),
        )

        customerBookRequestValidator
          .validatedCustomerBookIndividualPutRequest(customerBookIndividualPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", NonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
              InvalidFieldError(
                "phoneNationalNumber",
                "PhoneNationalNumber could not be validated using phoneNumberUtil, phoneCountryCode: [ +4 4 ], phoneNationalNumber: [77 347 7752 ], supportedPhoneRegions: [CY,GB], error: [None]",
                List("77 347 7752 "),
                index = 0,
              ),
              InvalidFieldError("city", NonEmptyTrimmedError, List(""), index = 0),
            )
          )
      }
    }

    "validatedCustomerBookBusinessPutRequest" should {
      "preserve omitted business fields and default contact action lists" in {
        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(smithy.CustomerBookBusinessPutRequest())
          .zioValue shouldBe CustomerBookBusinessPutRequest(None, None, None, None, None, Nil, Nil, Nil)
      }

      "preserve omitted contact fields as keep operations" in {
        val customerBusinessContactID            = arbitrarySample[CustomerBusinessContactID]
        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          customerBusinessContactsUpdate = List(smithy.CustomerBusinessContactUpdate(customerBusinessContactID.value))
        )

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(customerBookBusinessPutRequestSmithy)
          .zioValue shouldBe
          CustomerBookBusinessPutRequest(
            None,
            None,
            None,
            None,
            None,
            Nil,
            List(CustomerBusinessContactUpdate(customerBusinessContactID, None, None, None, None)),
            Nil,
          )
      }

      "preserve empty customer lists and nullable nulls as clear operations" in {
        val customerBusinessContactID            = arbitrarySample[CustomerBusinessContactID]
        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          emails = Some(Nil),
          taxID = Some(Nullable.Null),
          phoneNumbers = Some(Nil),
          addresses = Some(Nil),
          customerBusinessContactsUpdate = List(
            smithy.CustomerBusinessContactUpdate(
              customerBusinessContactID.value,
              role = Some(Nullable.Null),
              email = Some(Nullable.Null),
              phoneNumber = Some(Nullable.Null),
            )
          ),
        )

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(customerBookBusinessPutRequestSmithy)
          .zioValue shouldBe
          CustomerBookBusinessPutRequest(
            None,
            Some(Nil),
            Some(None),
            Some(Nil),
            Some(Nil),
            Nil,
            List(CustomerBusinessContactUpdate(customerBusinessContactID, None, Some(None), Some(None), Some(None))),
            Nil,
          )
      }

      "successfully validate a valid business update" in {
        val customerBookBusinessPutRequest = arbitrarySample[CustomerBookBusinessPutRequest]

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(
            customerBookBusinessPutRequest.transformInto[smithy.CustomerBookBusinessPutRequest]
          )
          .zioValue shouldBe customerBookBusinessPutRequest
      }

      "validate populated customer lists and nullable replacements with contact actions" in {
        val customerBusinessContactInsert = arbitrarySample[CustomerBusinessContactInsert]

        val customerBusinessContactUpdate = CustomerBusinessContactUpdate(
          arbitrarySample[CustomerBusinessContactID],
          Some(arbitrarySample[CustomerFullName]),
          Some(Some(arbitrarySample[CustomerBusinessContactRole])),
          Some(Some(arbitrarySample[CustomerEmail])),
          Some(Some(arbitrarySample[CustomerPhoneNumber])),
        )
        val customerBusinessContactArchive = CustomerBusinessContactArchive(arbitrarySample[CustomerBusinessContactID])
        val customerBookBusinessPutRequest = CustomerBookBusinessPutRequest(
          Some(arbitrarySample[CustomerBusinessName]),
          Some(
            List(arbitrarySample[CustomerEmailEntry].copy(isDefault = true))
          ),
          Some(Some(arbitrarySample[CustomerTaxID])),
          Some(
            List(
              arbitrarySample[CustomerPhoneNumberEntry]
                .copy(isDefault = true)
            )
          ),
          Some(
            List(CustomerAddressEntry(arbitrarySample[AddressEntry].copy(addressType = AddressType.ShippingAndBilling)))
          ),
          List(customerBusinessContactInsert),
          List(customerBusinessContactUpdate),
          List(customerBusinessContactArchive),
        )

        customerBusinessContactUpdate.customerBusinessContactID shouldNot equal(
          customerBusinessContactArchive.customerBusinessContactID
        )

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(
            customerBookBusinessPutRequest.transformInto[smithy.CustomerBookBusinessPutRequest]
          )
          .zioValue shouldBe customerBookBusinessPutRequest
      }

      "allow contacts to share email and phone values" in {
        val customerBusinessContactInsert = arbitrarySample[CustomerBusinessContactInsert]
          .copy(email = Some(arbitrarySample[CustomerEmail]), phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]))
        val customerBookBusinessPutRequest = CustomerBookBusinessPutRequest(
          None,
          None,
          None,
          None,
          None,
          List(customerBusinessContactInsert, customerBusinessContactInsert),
          Nil,
          Nil,
        )

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(
            customerBookBusinessPutRequest.transformInto[smithy.CustomerBookBusinessPutRequest]
          )
          .zioValue shouldBe customerBookBusinessPutRequest
      }

      "fail with a ValidationError accumulating parent and indexed contact errors in request order" in {
        val customerBusinessContactID           = arbitrarySample[CustomerBusinessContactID]
        val phoneNumberRequestSmithy            = smithy.PhoneNumberRequest("77 347 7752 ", " +4 4 ")
        val customerBusinessContactInsertSmithy = arbitrarySample[CustomerBusinessContactInsert]
          .transformInto[smithy.CustomerBusinessContactInsert]
        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          businessName = Some(""),
          emails = Some(List(smithy.CustomerEmailEntry("invalid-email", isDefault = true))),
          taxID = Some(Nullable.Value("")),
          phoneNumbers = Some(List(smithy.CustomerPhoneNumberEntry(phoneNumberRequestSmithy, isDefault = true))),
          addresses = Some(
            List(
              smithy.CustomerAddressEntry(
                smithy.AddressRequest(addressLine1 = Some("1 Main Street"), city = Some("")),
                smithy.AddressType.ShippingAndBilling,
              )
            )
          ),
          customerBusinessContactsInsert = List(
            customerBusinessContactInsertSmithy,
            smithy.CustomerBusinessContactInsert(
              "",
              role = Some(""),
              email = Some("invalid-email"),
              phoneNumber = Some(phoneNumberRequestSmithy),
            ),
          ),
          customerBusinessContactsUpdate = List(
            smithy.CustomerBusinessContactUpdate(arbitrarySample[CustomerBusinessContactID].value),
            smithy.CustomerBusinessContactUpdate(
              customerBusinessContactID.value,
              fullName = Some(""),
              role = Some(Nullable.Value("")),
              email = Some(Nullable.Value("invalid-email")),
              phoneNumber = Some(Nullable.Value(phoneNumberRequestSmithy)),
            ),
          ),
        )
        val fullNameError    = InvalidFieldError("fullName", NonEmptyTrimmedError, List(""))
        val roleError        = InvalidFieldError("role", NonEmptyTrimmedError, List(""))
        val emailError       = emailFormatError("invalid-email")
        val phoneNumberError = InvalidFieldError(
          "phoneNationalNumber",
          "PhoneNationalNumber could not be validated using phoneNumberUtil, phoneCountryCode: [ +4 4 ], phoneNationalNumber: [77 347 7752 ], supportedPhoneRegions: [CY,GB], error: [None]",
          List("77 347 7752 "),
        )
        val contactErrors = List(fullNameError, roleError, emailError, phoneNumberError).mkString("[", ", ", "]")

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(customerBookBusinessPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              InvalidFieldError("businessName", NonEmptyTrimmedError, List("")),
              emailError,
              InvalidFieldError("taxID", NonEmptyTrimmedError, List("")),
              phoneNumberError,
              InvalidFieldError("city", NonEmptyTrimmedError, List("")),
              InvalidFieldError(
                "customerBusinessContactInsert",
                s"Failed with invalid fields $contactErrors",
                Nil,
                index = 1,
              ),
              InvalidFieldError(
                "customerBusinessContactUpdate",
                s"Failed with invalid fields $contactErrors",
                Nil,
                index = 1,
              ),
            )
          )
      }

      "fail with a ValidationError for duplicate and overlapping contact IDs in stable action order" in {
        val customerBusinessContactID            = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactUpdateSmithy  = smithy.CustomerBusinessContactUpdate(customerBusinessContactID.value)
        val customerBusinessContactArchiveSmithy =
          smithy.CustomerBusinessContactArchive(customerBusinessContactID.value)
        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          customerBusinessContactsUpdate =
            List(customerBusinessContactUpdateSmithy, customerBusinessContactUpdateSmithy),
          customerBusinessContactsArchive =
            List(customerBusinessContactArchiveSmithy, customerBusinessContactArchiveSmithy),
        )
        val submittedValues = List(customerBusinessContactID.value.toString)

        customerBookRequestValidator
          .validatedCustomerBookBusinessPutRequest(customerBookBusinessPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              InvalidFieldError(
                "customerBusinessContactsUpdate",
                "Contact ID must be unique within this list",
                submittedValues,
                index = 1,
              ),
              InvalidFieldError(
                "customerBusinessContactsArchive",
                "Contact ID must be unique within this list",
                submittedValues,
                index = 1,
              ),
              InvalidFieldError(
                "customerBusinessContactsArchive",
                "Contact ID must not also appear in customerBusinessContactsUpdate",
                submittedValues,
                index = 0,
              ),
              InvalidFieldError(
                "customerBusinessContactsArchive",
                "Contact ID must not also appear in customerBusinessContactsUpdate",
                submittedValues,
                index = 1,
              ),
            )
          )
      }
    }

    "validatedCustomerBookUniquenessGetRequest" should {
      "successfully validate the complete query with a self-exclusion ID" in {
        val organizationID                   = arbitrarySample[OrganizationID]
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
          customerType = CustomerType.Individual,
          customerID = Some(arbitrarySample[CustomerID]),
        )
        val customerBookUniquenessGetInputSmithy = customerBookUniquenessGetRequest
          .into[smithy.CustomerBookUniquenessGetInput]
          .withFieldConst(_.organizationID, organizationID.value)
          .transform

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioValue shouldBe customerBookUniquenessGetRequest
      }

      "successfully validate the complete query without a self-exclusion ID" in {
        val organizationID                   = arbitrarySample[OrganizationID]
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
          customerType = CustomerType.Business,
          customerID = None,
        )
        val customerBookUniquenessGetInputSmithy = customerBookUniquenessGetRequest
          .into[smithy.CustomerBookUniquenessGetInput]
          .withFieldConst(_.organizationID, organizationID.value)
          .transform

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioValue shouldBe customerBookUniquenessGetRequest
      }

      "fail with a ValidationError when the name is absent" in {
        val customerBookUniquenessGetInputSmithy =
          arbitrarySample[smithy.CustomerBookUniquenessGetInput].copy(name = None)

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", "Name must be provided", Seq.empty)
          )
        )
      }

      "fail with a ValidationError when the customer type is absent" in {
        val customerBookUniquenessGetInputSmithy =
          arbitrarySample[smithy.CustomerBookUniquenessGetInput].copy(customerType = None)

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("customerType", "Customer type must be provided", Seq.empty)
          )
        )
      }

      "fail with a ValidationError accumulating both absent fields in query order" in {
        val customerBookUniquenessGetInputSmithy =
          arbitrarySample[smithy.CustomerBookUniquenessGetInput].copy(name = None, customerType = None)

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", "Name must be provided", Seq.empty),
            InvalidFieldError("customerType", "Customer type must be provided", Seq.empty),
          )
        )
      }

      "fail with a ValidationError accumulating an empty name and absent customer type" in {
        val nameInvalid                          = ""
        val customerBookUniquenessGetInputSmithy =
          arbitrarySample[smithy.CustomerBookUniquenessGetInput].copy(name = Some(nameInvalid), customerType = None)

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", NonEmptyTrimmedError, List(nameInvalid)),
            InvalidFieldError("customerType", "Customer type must be provided", Seq.empty),
          )
        )
      }

      "fail with a ValidationError preserving an untrimmed name" in {
        val nameInvalid                          = " untrimmed name "
        val customerBookUniquenessGetInputSmithy =
          arbitrarySample[smithy.CustomerBookUniquenessGetInput].copy(name = Some(nameInvalid))

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", NonEmptyTrimmedError, List(nameInvalid))
          )
        )
      }

      "fail with a ValidationError preserving an overlong name" in {
        val nameLengthInvalid                    = 256
        val nameCharacter                        = "n"
        val nameInvalid                          = nameCharacter * nameLengthInvalid
        val customerBookUniquenessGetInputSmithy =
          arbitrarySample[smithy.CustomerBookUniquenessGetInput].copy(name = Some(nameInvalid))

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", NonEmptyTrimmedError, List(nameInvalid))
          )
        )
      }

      "successfully validate a name at the minimum length" in {
        val organizationID                   = arbitrarySample[OrganizationID]
        val nameLengthBoundary               = 1
        val nameCharacter                    = "n"
        val nameBoundary                     = nameCharacter * nameLengthBoundary
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]
          .copy(name = CustomerName.either(nameBoundary).toOption.value)
        val customerBookUniquenessGetInputSmithy = customerBookUniquenessGetRequest
          .into[smithy.CustomerBookUniquenessGetInput]
          .withFieldConst(_.organizationID, organizationID.value)
          .transform

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioValue shouldBe customerBookUniquenessGetRequest
      }

      "successfully validate a name at the maximum length" in {
        val organizationID                   = arbitrarySample[OrganizationID]
        val nameLengthBoundary               = 255
        val nameCharacter                    = "n"
        val nameBoundary                     = nameCharacter * nameLengthBoundary
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]
          .copy(name = CustomerName.either(nameBoundary).toOption.value)
        val customerBookUniquenessGetInputSmithy = customerBookUniquenessGetRequest
          .into[smithy.CustomerBookUniquenessGetInput]
          .withFieldConst(_.organizationID, organizationID.value)
          .transform

        customerBookRequestValidator
          .validatedCustomerBookUniquenessGetRequest(customerBookUniquenessGetInputSmithy)
          .zioValue shouldBe customerBookUniquenessGetRequest
      }
    }

  }
}
