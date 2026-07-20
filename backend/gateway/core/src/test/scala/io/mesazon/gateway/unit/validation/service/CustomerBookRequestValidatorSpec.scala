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
        val individual = arbitrarySample[InsertCustomerIndividualPostRequest]

        validator
          .validatedInsertCustomerIndividualPostRequest(
            individual.transformInto[smithy.InsertCustomerIndividualPostRequest]
          )
          .zioValue shouldBe individual
      }

      "accumulate every field error" in {
        val request = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]
          .copy(
            fullName = "",
            emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          )

        validator.validatedInsertCustomerIndividualPostRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
              InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email")),
            )
          )
      }

      "reject when more than one email is marked default" in {
        val request = arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
          emails = List(
            smithy.CustomerEmailEntryRequest(email = "a@example.com", isDefault = true),
            smithy.CustomerEmailEntryRequest(email = "b@example.com", isDefault = true),
          ),
          phoneNumbers = Nil,
        )

        validator.validatedInsertCustomerIndividualPostRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("emails", "Exactly one entry must be marked as default", List())
            )
          )
      }

      "reject when no email is marked default" in {
        val request = arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
          emails = List(smithy.CustomerEmailEntryRequest(email = "a@example.com", isDefault = false)),
          phoneNumbers = Nil,
        )

        validator.validatedInsertCustomerIndividualPostRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("emails", "Exactly one entry must be marked as default", List())
            )
          )
      }
    }

    "validatedInsertCustomerIndividualsPostRequest" should {
      "successfully validate a batch of individuals" in {
        val individuals = arbitrarySample[InsertCustomerIndividualsPostRequest]

        validator
          .validatedInsertCustomerIndividualsPostRequest(
            individuals.transformInto[smithy.InsertCustomerIndividualsPostRequest]
          )
          .zioValue shouldBe individuals
      }

      "wrap each invalid individual's errors under the batch field, tagged with the individual's index" in {
        val request = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest].copy(
          customerIndividuals = List(
            arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = ""),
            arbitrarySample[smithy.InsertCustomerIndividualPostRequest]
              .copy(emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = true))),
          )
        )

        val fullNameError = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))
        val emailError    =
          InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email"))

        validator.validatedInsertCustomerIndividualsPostRequest(request).zioError shouldBe
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
        val invalidEmailsIndividual = arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(
          emails = List(
            smithy.CustomerEmailEntryRequest(email = "bad-1", isDefault = false),
            smithy.CustomerEmailEntryRequest(email = "ok@example.com", isDefault = true),
            smithy.CustomerEmailEntryRequest(email = "bad-2", isDefault = false),
          )
        )

        val request = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest].copy(
          customerIndividuals = List(
            arbitrarySample[InsertCustomerIndividualPostRequest]
              .transformInto[smithy.InsertCustomerIndividualPostRequest],
            invalidEmailsIndividual,
            arbitrarySample[InsertCustomerIndividualPostRequest]
              .transformInto[smithy.InsertCustomerIndividualPostRequest],
            arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = ""),
          )
        )

        val firstEmailError =
          InvalidFieldError("email", "Invalid email format: [bad-1], error: [null]", List("bad-1"), index = 0)
        val thirdEmailError =
          InvalidFieldError("email", "Invalid email format: [bad-2], error: [null]", List("bad-2"), index = 2)
        val fullNameError = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))

        validator.validatedInsertCustomerIndividualsPostRequest(request).zioError shouldBe
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
        val business = arbitrarySample[InsertCustomerBusinessPostRequest]

        validator
          .validatedInsertCustomerBusinessPostRequest(business.transformInto[smithy.InsertCustomerBusinessPostRequest])
          .zioValue shouldBe business
      }

      "accumulate business and nested contact errors" in {
        val request = arbitrarySample[smithy.InsertCustomerBusinessPostRequest].copy(
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

        validator.validatedInsertCustomerBusinessPostRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("businessName", nonEmptyTrimmedError, List("")),
              InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email")),
              InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
            )
          )
      }
    }

    "validatedInsertCustomerBusinessesPostRequest" should {
      "successfully validate a batch of businesses" in {
        val businesses = arbitrarySample[InsertCustomerBusinessesPostRequest]

        validator
          .validatedInsertCustomerBusinessesPostRequest(
            businesses.transformInto[smithy.InsertCustomerBusinessesPostRequest]
          )
          .zioValue shouldBe businesses
      }

      "wrap each invalid business's errors under the batch field, tagged with the business's index" in {
        val request = arbitrarySample[smithy.InsertCustomerBusinessesPostRequest].copy(
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
        val emailError        =
          InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email"))

        validator.validatedInsertCustomerBusinessesPostRequest(request).zioError shouldBe
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
        val customers = arbitrarySample[InsertCustomersPostRequest]

        validator
          .validatedInsertCustomersPostRequest(customers.transformInto[smithy.InsertCustomersPostRequest])
          .zioValue shouldBe customers
      }

      "accumulate errors across businesses and individuals" in {
        val request = arbitrarySample[smithy.InsertCustomersPostRequest].copy(
          customerBusinesses = List(
            arbitrarySample[smithy.InsertCustomerBusinessPostRequest]
              .copy(businessName = "", customerBusinessContacts = None)
          ),
          customerIndividuals = List(arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = "")),
        )

        val businessNameError = InvalidFieldError("businessName", nonEmptyTrimmedError, List(""))
        val fullNameError     = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))

        validator.validatedInsertCustomersPostRequest(request).zioError shouldBe
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
        val update = arbitrarySample[UpdateCustomerIndividualPutRequest]

        validator
          .validatedUpdateCustomerIndividualPutRequest(update.transformInto[smithy.UpdateCustomerIndividualPutRequest])
          .zioValue shouldBe update
      }

      "accumulate every field error" in {
        val request = arbitrarySample[smithy.UpdateCustomerIndividualPutRequest]
          .copy(
            fullName = Some(""),
            emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          )

        validator.validatedUpdateCustomerIndividualPutRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("fullName", nonEmptyTrimmedError, List("")),
              InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email")),
            )
          )
      }
    }

    "validatedUpdateCustomerBusinessPutRequest" should {
      "successfully validate a business update" in {
        val update = arbitrarySample[UpdateCustomerBusinessPutRequest]

        validator
          .validatedUpdateCustomerBusinessPutRequest(update.transformInto[smithy.UpdateCustomerBusinessPutRequest])
          .zioValue shouldBe update
      }

      "accumulate every field error" in {
        val request = arbitrarySample[smithy.UpdateCustomerBusinessPutRequest]
          .copy(
            businessName = Some(""),
            emails = List(smithy.CustomerEmailEntryRequest(email = "invalid-email", isDefault = false)),
          )

        validator.validatedUpdateCustomerBusinessPutRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("businessName", nonEmptyTrimmedError, List("")),
              InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email")),
            )
          )
      }
    }

    "validatedAddCustomerBusinessContactsPutRequest" should {
      "successfully validate contacts to add" in {
        val contacts = arbitrarySample[AddCustomerBusinessContactsPutRequest]

        validator
          .validatedAddCustomerBusinessContactsPutRequest(
            contacts.transformInto[smithy.AddCustomerBusinessContactsPutRequest]
          )
          .zioValue shouldBe contacts
      }

      "accumulate errors tagged with the index of each invalid contact" in {
        val request = arbitrarySample[smithy.AddCustomerBusinessContactsPutRequest].copy(
          customerBusinessContacts = List(
            arbitrarySample[smithy.AddCustomerBusinessContact].copy(fullName = ""),
            arbitrarySample[smithy.AddCustomerBusinessContact].copy(email = Some("invalid-email")),
          )
        )

        validator.validatedAddCustomerBusinessContactsPutRequest(request).zioError shouldBe
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
