package io.mesazon.gateway.unit.validation

import io.github.iltotore.iron.*
import io.mesazon.domain.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.Mocks.phoneNumberRegionValidatorMockLive
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.gateway.validation.{ServiceValidator, UserManagementValidators}
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import zio.*

class UserManagementValidatorsSpec extends ZWordSpecBase, SmithyArbitraries {

  "UserManagementValidators" when {
    "onboardUserDetailsRequestValidator" should {
      "return OnboardUserDetails when all fields are valid" in {
        val onboardUserDetails        = arbitrarySample[OnboardUserDetails]
        val phoneRegion               = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val phoneNationalNumber       = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val onboardUserDetailsRequest =
          onboardUserDetails
            .into[smithy.OnboardUserDetailsRequest]
            .withFieldConst(_.phoneRegion, phoneRegion)
            .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
            .transform

        val validator = ZIO
          .service[ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(
            UserManagementValidators.onboardUserDetailsRequestValidatorLive,
            phoneNumberRegionValidatorMockLive(),
          )
          .zioValue

        validator.validate(onboardUserDetailsRequest).zioValue shouldBe onboardUserDetails.copy(phoneNumber =
          PhoneNumberE164.assume(phoneNationalNumber)
        )
      }

      "return all invalid fields when 1 or more fail validation" in {
        val onboardUserDetailsRequest =
          arbitrarySample[smithy.OnboardUserDetailsRequest].copy(firstName = " ", lastName = "")

        val validator = ZIO
          .service[ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(
            UserManagementValidators.onboardUserDetailsRequestValidatorLive,
            phoneNumberRegionValidatorMockLive(),
          )
          .zioValue

        validator.validate(onboardUserDetailsRequest).zioError shouldBe
          BadRequestError.FormValidationError(
            Seq(
              InvalidFieldError(
                "firstName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                onboardUserDetailsRequest.firstName,
              ),
              InvalidFieldError(
                "lastName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                onboardUserDetailsRequest.lastName,
              ),
            )
          )
      }

      "return all invalid fields when all fail validation" in {
        val onboardUserDetailsRequest = smithy.OnboardUserDetailsRequest(
          firstName = "",
          lastName = "",
          phoneRegion = "",
          phoneNationalNumber = "",
          addressLine1 = "",
          city = "",
          postalCode = "",
          company = "",
          addressLine2 = Some(""),
        )

        val validator = ZIO
          .service[ServiceValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails]]
          .provide(
            UserManagementValidators.onboardUserDetailsRequestValidatorLive,
            phoneNumberRegionValidatorMockLive(),
          )
          .zioValue

        validator
          .validate(onboardUserDetailsRequest)
          .zioError
          .asInstanceOf[BadRequestError.FormValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq(
          "firstName",
          "lastName",
          "addressLine1",
          "addressLine2",
          "city",
          "postalCode",
          "company",
        )
      }
    }

    "updateUserDetailsRequestValidator" should {
      "return UpdateUserDetails when all fields are valid" in {
        val updateUserDetails        = arbitrarySample[UpdateUserDetails]
        val phoneRegion              = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val phoneNationalNumber      = arbitrarySample[String :| NonEmptyTrimmedLowerCase]
        val updateUserDetailsRequest =
          updateUserDetails
            .into[smithy.UpdateUserDetailsRequest]
            .withFieldConst(_.phoneRegion, Some(phoneRegion))
            .withFieldConst(_.phoneNationalNumber, Some(phoneNationalNumber))
            .transform

        val validator = ZIO
          .service[ServiceValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails]]
          .provide(UserManagementValidators.updateUserDetailsRequestValidatorLive, phoneNumberRegionValidatorMockLive())
          .zioValue

        validator.validate(updateUserDetailsRequest).zioValue shouldBe updateUserDetails.copy(phoneNumber =
          Some(PhoneNumberE164.assume(phoneNationalNumber))
        )
      }

      "return all invalid fields when 1 or more fail validation" in {
        val updateUserDetailsRequest =
          arbitrarySample[smithy.UpdateUserDetailsRequest].copy(firstName = Some(" "), lastName = Some(""))

        val validator = ZIO
          .service[ServiceValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails]]
          .provide(UserManagementValidators.updateUserDetailsRequestValidatorLive, phoneNumberRegionValidatorMockLive())
          .zioValue

        validator.validate(updateUserDetailsRequest).zioError shouldBe
          BadRequestError.FormValidationError(
            Seq(
              InvalidFieldError(
                "firstName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                updateUserDetailsRequest.firstName.value,
              ),
              InvalidFieldError(
                "lastName",
                "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
                updateUserDetailsRequest.lastName.value,
              ),
            )
          )
      }

      "return failure when request fields are all empty" in {
        val updateUserDetailsRequest = smithy.UpdateUserDetailsRequest()

        val validator = ZIO
          .service[ServiceValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails]]
          .provide(UserManagementValidators.updateUserDetailsRequestValidatorLive, phoneNumberRegionValidatorMockLive())
          .zioValue

        validator
          .validate(updateUserDetailsRequest)
          .zioError
          .asInstanceOf[BadRequestError.FormValidationError]
          .invalidFields shouldBe Seq(
          InvalidFieldError("updateUserDetailsRequest", "request received all fields are empty", Seq.empty)
        )
      }

      "return all invalid fields when all fail validation" in {
        val updateUserDetailsRequest = smithy.UpdateUserDetailsRequest(
          firstName = Some(""),
          lastName = Some(""),
          phoneRegion = Some(""),
          phoneNationalNumber = Some(""),
          addressLine1 = Some(""),
          city = Some(""),
          postalCode = Some(""),
          company = Some(""),
          addressLine2 = Some(""),
        )

        val validator = ZIO
          .service[ServiceValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails]]
          .provide(UserManagementValidators.updateUserDetailsRequestValidatorLive, phoneNumberRegionValidatorMockLive())
          .zioValue

        validator
          .validate(updateUserDetailsRequest)
          .zioError
          .asInstanceOf[BadRequestError.FormValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq(
          "firstName",
          "lastName",
          "addressLine1",
          "addressLine2",
          "city",
          "postalCode",
          "company",
        )
      }
    }
  }
}
