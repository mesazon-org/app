package io.mesazon.gateway.unit.validation

import io.github.iltotore.iron.:|
import io.mesazon.domain.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.Mocks.phoneNumberRegionValidatorLive
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.gateway.validation.*
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.into
import zio.*

class UserContactsValidatorsSpec extends ZWordSpecBase, SmithyArbitraries {

  "UserContactsValidators" when {
    "upsertUserContactsValidator" should {
      "return UpsertUserContacts when all fields are valid" in {
        val upsertUserContacts        = arbitrarySample[UpsertUserContact](5).toVector
        val phoneRegion               = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val phoneNationalNumber       = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val upsertUserContactsRequest =
          upsertUserContacts
            .map(
              _.into[smithy.UpsertUserContactRequest]
                .withFieldConst(_.phoneRegion, phoneRegion)
                .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
                .transform
            )
            .toSet

        val validator = ZIO
          .service[ServiceValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[UpsertUserContact]]]
          .provide(UserContactsValidators.upsertUserContactsValidatorLive, phoneNumberRegionValidatorLive())
          .zioValue

        validator
          .validate(upsertUserContactsRequest)
          .zioValue
          .toList should contain theSameElementsAs upsertUserContacts
          .map(
            _.copy(phoneNumber = PhoneNumberE164.assume(phoneNationalNumber))
          )
          .toList
      }

      "return all invalid fields when 1 or more fail validation" in {
        val upsertUserContact1 = arbitrarySample[UpsertUserContact]
          .copy(firstName = FirstName.assume(""))
        val upsertUserContact2 = arbitrarySample[UpsertUserContact]
          .copy(firstName = FirstName.assume(" "), lastName = Some(LastName.assume(" ")))
        val upsertUserContact3 = arbitrarySample[UpsertUserContact]
          .copy(userContactID = Some(UserContactID.assume("")))
        val phoneRegion               = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val phoneNationalNumber       = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val upsertUserContacts        = Vector(upsertUserContact1, upsertUserContact2, upsertUserContact3)
        val upsertUserContactsRequest =
          upsertUserContacts
            .map(
              _.into[smithy.UpsertUserContactRequest]
                .withFieldConst(_.phoneRegion, phoneRegion)
                .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
                .transform
            )
            .toSet

        val validator = ZIO
          .service[ServiceValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[UpsertUserContact]]]
          .provide(UserContactsValidators.upsertUserContactsValidatorLive, phoneNumberRegionValidatorLive())
          .zioValue

        validator.validate(upsertUserContactsRequest).zioError shouldBe
          BadRequestError.FormValidationError(
            Seq(
              InvalidFieldError(
                "firstName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                upsertUserContact1.firstName.value,
              ),
              InvalidFieldError(
                "firstName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                upsertUserContact2.firstName.value,
              ),
              InvalidFieldError(
                "lastName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                upsertUserContact2.lastName.value.value,
              ),
              InvalidFieldError(
                "userContactID",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                upsertUserContact3.userContactID.get.value,
              ),
            )
          )
      }

      "return all invalid fields when all fail validation" in {
        val upsertUserContactRequest = smithy.UpsertUserContactRequest(
          userContactID = Some(""),
          displayName = " ",
          firstName = " ",
          phoneRegion = "",
          phoneNationalNumber = "",
          lastName = Some(" "),
          email = Some(""),
          addressLine1 = Some(""),
          addressLine2 = Some(""),
          city = Some(""),
          postalCode = Some(""),
          company = Some(""),
        )

        val validator = ZIO
          .service[ServiceValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[UpsertUserContact]]]
          .provide(UserContactsValidators.upsertUserContactsValidatorLive, phoneNumberRegionValidatorLive())
          .zioValue

        validator
          .validate(Set(upsertUserContactRequest))
          .zioError
          .asInstanceOf[BadRequestError.FormValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe
          Seq(
            "userContactID",
            "displayName",
            "firstName",
            "lastName",
            "email",
            "addressLine1",
            "addressLine2",
            "city",
            "postalCode",
            "company",
          )
      }

      "return failure when request set is empty" in {
        val validator = ZIO
          .service[ServiceValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[UpsertUserContact]]]
          .provide(UserContactsValidators.upsertUserContactsValidatorLive, phoneNumberRegionValidatorLive())
          .zioValue

        validator
          .validate(Set.empty)
          .zioError
          .asInstanceOf[BadRequestError.FormValidationError]
          .invalidFields shouldBe Seq(
          InvalidFieldError("upsertUserContactRequest", "request received contained empty collection", Seq.empty)
        )
      }
    }
  }
}
