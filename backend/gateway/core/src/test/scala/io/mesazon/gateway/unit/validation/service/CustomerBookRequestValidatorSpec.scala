package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.*

class CustomerBookRequestValidatorSpec extends ZWordSpecBase, CustomerBookSmithyArbitraries {

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private def emailFormatError(rawEmail: String, index: Int = 0) =
    InvalidFieldError("email", s"Invalid email format: [$rawEmail], error: [null]", List(rawEmail), index = index)

  private def validEmailSmithy(index: Int, isDefault: Boolean = false) =
    smithy.CustomerEmailEntryRequest(email = s"user$index@example.com", isDefault = isDefault)

  private val validator: CustomerBookRequestValidator = ZIO
    .service[CustomerBookRequestValidator]
    .provide(
      CustomerBookRequestValidator.live,
      EmailValidator.live,
      PhoneNumberDomainValidator.live,
      PhoneNumberUtil.live,
      ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
    )
    .zioValue

  "CustomerBookRequestValidator" should {

    "validatedInsertCustomerIndividualPostRequest" should {
      "successfully validate a valid individual" in {
        val insertCustomerIndividualPostRequest = arbitrarySample[InsertCustomerIndividualPostRequest]

        validator
          .validatedInsertCustomerIndividualPostRequest(
            insertCustomerIndividualPostRequest.transformInto[smithy.InsertCustomerIndividualPostRequest]
          )
          .zioValue shouldBe insertCustomerIndividualPostRequest
      }

      "accumulate every field error" in {
        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]
          .copy(
            fullName = "",
            emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          )

        validator
          .validatedInsertCustomerIndividualPostRequest(insertCustomerIndividualPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
            )
          )
      }

      "report only the failing entries of an email list, tagging each with its list index" in {
        val insertCustomerIndividualPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
            emails = List(
              validEmailSmithy(0, isDefault = true),
              smithy.CustomerEmailEntryRequest(email = "bad-1", isDefault = false),
              validEmailSmithy(2),
              smithy.CustomerEmailEntryRequest(email = "bad-3", isDefault = false),
            ),
            phoneNumbers = Nil,
          )

        validator
          .validatedInsertCustomerIndividualPostRequest(insertCustomerIndividualPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              emailFormatError("bad-1", index = 1),
              emailFormatError("bad-3", index = 3),
            )
          )
      }

      "reject when more than one email is marked default" in {
        val insertCustomerIndividualPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
            emails = List(validEmailSmithy(0, isDefault = true), validEmailSmithy(1, isDefault = true)),
            phoneNumbers = Nil,
          )

        validator
          .validatedInsertCustomerIndividualPostRequest(insertCustomerIndividualPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("emails", "Exactly one entry must be marked as default", List())
            )
          )
      }

      "reject when no email is marked default" in {
        val insertCustomerIndividualPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
            emails = List(validEmailSmithy(0, isDefault = false)),
            phoneNumbers = Nil,
          )

        validator
          .validatedInsertCustomerIndividualPostRequest(insertCustomerIndividualPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("emails", "Exactly one entry must be marked as default", List())
            )
          )
      }
    }

    "validatedInsertCustomerIndividualsPostRequest" should {
      "successfully validate a batch of individuals" in {
        val insertCustomerIndividualsPostRequest = arbitrarySample[InsertCustomerIndividualsPostRequest]

        validator
          .validatedInsertCustomerIndividualsPostRequest(
            insertCustomerIndividualsPostRequest.transformInto[smithy.InsertCustomerIndividualsPostRequest]
          )
          .zioValue shouldBe insertCustomerIndividualsPostRequest
      }

      "wrap each invalid individual's errors under the batch field, tagged with the individual's index" in {
        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]
          .copy(
            customerIndividuals = List(
              arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = ""),
              arbitrarySample[smithy.InsertCustomerIndividualPostRequest]
                .copy(emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = true))),
            )
          )

        val fullNameError = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))
        val emailError    = emailFormatError("invalid-email")

        validator
          .validatedInsertCustomerIndividualsPostRequest(insertCustomerIndividualsPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError(
                "customerIndividuals",
                s"Failed with invalid fields [$fullNameError]",
                List(),
                index = 0,
              ),
              InvalidFieldError("customerIndividuals", s"Failed with invalid fields [$emailError]", List(), index = 1),
            )
          )
      }

      "report only the failing individuals of a mixed batch, keeping the inner email indexes in the message" in {
        val invalidEmailsIndividualSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
          emails = List(
            smithy.CustomerEmailEntryRequest(email = "bad-1", isDefault = false),
            smithy.CustomerEmailEntryRequest(email = "ok@example.com", isDefault = true),
            smithy.CustomerEmailEntryRequest(email = "bad-2", isDefault = false),
          )
        )

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]
          .copy(
            customerIndividuals = List(
              arbitrarySample[InsertCustomerIndividualPostRequest]
                .transformInto[smithy.InsertCustomerIndividualPostRequest],
              invalidEmailsIndividualSmithy,
              arbitrarySample[InsertCustomerIndividualPostRequest]
                .transformInto[smithy.InsertCustomerIndividualPostRequest],
              arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = ""),
            )
          )

        val firstEmailError = emailFormatError("bad-1", index = 0)
        val thirdEmailError = emailFormatError("bad-2", index = 2)
        val fullNameError   = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))

        validator
          .validatedInsertCustomerIndividualsPostRequest(insertCustomerIndividualsPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError(
                "customerIndividuals",
                s"Failed with invalid fields [$firstEmailError, $thirdEmailError]",
                List(),
                index = 1,
              ),
              InvalidFieldError(
                "customerIndividuals",
                s"Failed with invalid fields [$fullNameError]",
                List(),
                index = 3,
              ),
            )
          )
      }
    }

    "validatedInsertCustomerBusinessPostRequest" should {
      "successfully validate a valid business" in {
        val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]

        validator
          .validatedInsertCustomerBusinessPostRequest(
            insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest]
          )
          .zioValue shouldBe insertCustomerBusinessPostRequest
      }

      "accumulate business and nested contact errors" in {
        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest].copy(
          businessName = "",
          emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          customerBusinessContacts = Some(
            List(
              arbitrarySample[smithy.AddCustomerBusinessContact]
                .transformInto[smithy.InsertCustomerBusinessContact]
                .copy(fullName = "")
            )
          ),
        )

        validator.validatedInsertCustomerBusinessPostRequest(insertCustomerBusinessPostRequestSmithy).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("businessName", nonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
              InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
            )
          )
      }
    }

    "validatedInsertCustomerBusinessesPostRequest" should {
      "successfully validate a batch of businesses" in {
        val insertCustomerBusinessesPostRequest = arbitrarySample[InsertCustomerBusinessesPostRequest]

        validator
          .validatedInsertCustomerBusinessesPostRequest(
            insertCustomerBusinessesPostRequest.transformInto[smithy.InsertCustomerBusinessesPostRequest]
          )
          .zioValue shouldBe insertCustomerBusinessesPostRequest
      }

      "wrap each invalid business's errors under the batch field, tagged with the business's index" in {
        val insertCustomerBusinessesPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessesPostRequest]
          .copy(
            customerBusinesses = List(
              arbitrarySample[smithy.InsertCustomerBusinessPostRequest]
                .copy(
                  businessName = "",
                  emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = true)),
                  customerBusinessContacts = None,
                )
            )
          )

        val businessNameError = InvalidFieldError("businessName", nonEmptyTrimmedError, List(""))
        val emailError        = emailFormatError("invalid-email")

        validator
          .validatedInsertCustomerBusinessesPostRequest(insertCustomerBusinessesPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError(
                "customerBusinesses",
                s"Failed with invalid fields [$businessNameError, $emailError]",
                List(),
                index = 0,
              )
            )
          )
      }
    }

    "validatedInsertCustomersPostRequest" should {
      "successfully validate businesses and individuals together" in {
        val insertCustomersPostRequest = arbitrarySample[InsertCustomersPostRequest]

        validator
          .validatedInsertCustomersPostRequest(
            insertCustomersPostRequest.transformInto[smithy.InsertCustomersPostRequest]
          )
          .zioValue shouldBe insertCustomersPostRequest
      }

      "accumulate errors across businesses and individuals" in {
        val insertCustomersPostRequestSmithy = arbitrarySample[smithy.InsertCustomersPostRequest].copy(
          customerBusinesses = List(
            arbitrarySample[smithy.InsertCustomerBusinessPostRequest]
              .copy(businessName = "", customerBusinessContacts = None)
          ),
          customerIndividuals = List(arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = "")),
        )

        val businessNameError = InvalidFieldError("businessName", nonEmptyTrimmedError, List(""))
        val fullNameError     = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))

        validator.validatedInsertCustomersPostRequest(insertCustomersPostRequestSmithy).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("customerBusinesses", s"Failed with invalid fields [$businessNameError]", List()),
              InvalidFieldError("customerIndividuals", s"Failed with invalid fields [$fullNameError]", List()),
            )
          )
      }
    }

    "validatedUpdateCustomerIndividualPutRequest" should {
      "successfully validate an individual update" in {
        val updateCustomerIndividualPutRequest = arbitrarySample[UpdateCustomerIndividualPutRequest]

        validator
          .validatedUpdateCustomerIndividualPutRequest(
            updateCustomerIndividualPutRequest.transformInto[smithy.UpdateCustomerIndividualPutRequest]
          )
          .zioValue shouldBe updateCustomerIndividualPutRequest
      }

      "accumulate every field error" in {
        val updateCustomerIndividualPutRequestSmithy = arbitrarySample[smithy.UpdateCustomerIndividualPutRequest]
          .copy(
            fullName = Some(""),
            emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          )

        validator
          .validatedUpdateCustomerIndividualPutRequest(updateCustomerIndividualPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
            )
          )
      }
    }

    "validatedUpdateCustomerBusinessPutRequest" should {
      "successfully validate a business update" in {
        val updateCustomerBusinessPutRequest = arbitrarySample[UpdateCustomerBusinessPutRequest]

        validator
          .validatedUpdateCustomerBusinessPutRequest(
            updateCustomerBusinessPutRequest.transformInto[smithy.UpdateCustomerBusinessPutRequest]
          )
          .zioValue shouldBe updateCustomerBusinessPutRequest
      }

      "accumulate every field error" in {
        val updateCustomerBusinessPutRequestSmithy = arbitrarySample[smithy.UpdateCustomerBusinessPutRequest]
          .copy(
            businessName = Some(""),
            emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          )

        validator.validatedUpdateCustomerBusinessPutRequest(updateCustomerBusinessPutRequestSmithy).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("businessName", nonEmptyTrimmedError, List("")),
              emailFormatError("invalid-email"),
            )
          )
      }
    }

    "validatedAddCustomerBusinessContactsPutRequest" should {
      "successfully validate contacts to add" in {
        val addCustomerBusinessContactsPutRequest = arbitrarySample[AddCustomerBusinessContactsPutRequest]

        validator
          .validatedAddCustomerBusinessContactsPutRequest(
            addCustomerBusinessContactsPutRequest.transformInto[smithy.AddCustomerBusinessContactsPutRequest]
          )
          .zioValue shouldBe addCustomerBusinessContactsPutRequest
      }

      "accumulate errors tagged with the index of each invalid contact" in {
        val addCustomerBusinessContactsPutRequestSmithy = arbitrarySample[smithy.AddCustomerBusinessContactsPutRequest]
          .copy(
            customerBusinessContacts = List(
              arbitrarySample[smithy.AddCustomerBusinessContact].copy(fullName = ""),
              arbitrarySample[smithy.AddCustomerBusinessContact].copy(email = Some("invalid-email")),
            )
          )

        validator
          .validatedAddCustomerBusinessContactsPutRequest(addCustomerBusinessContactsPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", nonEmptyTrimmedError, List(""), index = 0),
              InvalidFieldError(
                "email",
                "Invalid email format: [invalid-email], error: [null]",
                List("invalid-email"),
                index = 1,
              ),
            )
          )
      }
    }
  }
}
