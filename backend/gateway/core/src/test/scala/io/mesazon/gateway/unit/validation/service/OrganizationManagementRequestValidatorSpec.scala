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

class OrganizationManagementRequestValidatorSpec extends ZWordSpecBase, OrganizationManagementSmithyArbitraries {

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val validator: OrganizationManagementRequestValidator = ZIO
    .service[OrganizationManagementRequestValidator]
    .provide(
      OrganizationManagementRequestValidator.live,
      EmailValidator.live,
      PhoneNumberDomainValidator.live,
      PhoneNumberUtil.live,
      ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
    )
    .zioValue

  "OrganizationManagementRequestValidator" should {
    "successfully validate a valid request" in {
      val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

      validator
        .validatedCreateOrganizationPostRequest(
          createOrganizationPostRequest.transformInto[smithy.CreateOrganizationPostRequest]
        )
        .zioValue shouldBe createOrganizationPostRequest
    }

    "accumulate every field error" in {
      val request = arbitrarySample[smithy.CreateOrganizationPostRequest].copy(
        name = "",
        emails = List(smithy.OrganizationEmailEntryRequest(email = "invalid-email", isDefault = true)),
      )

      validator.validatedCreateOrganizationPostRequest(request).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("name", nonEmptyTrimmedError, List("")),
            InvalidFieldError("email", "Invalid email format: [invalid-email], error: [null]", List("invalid-email")),
          )
        )
    }

    "reject when more than one email is marked default" in {
      val request = arbitrarySample[smithy.CreateOrganizationPostRequest].copy(
        emails = List(
          smithy.OrganizationEmailEntryRequest(email = "a@example.com", isDefault = true),
          smithy.OrganizationEmailEntryRequest(email = "b@example.com", isDefault = true),
        ),
        phoneNumbers = Nil,
      )

      validator.validatedCreateOrganizationPostRequest(request).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("emails", "Exactly one entry must be marked as default", List())
          )
        )
    }

    "reject when no phone number is marked default" in {
      val request = arbitrarySample[smithy.CreateOrganizationPostRequest].copy(
        emails = Nil,
        phoneNumbers = List(
          smithy.OrganizationPhoneNumberEntryRequest(
            phoneNumber = smithy.PhoneNumberRequest(phoneNationalNumber = "99135215", phoneCountryCode = "+357"),
            isDefault = false,
          )
        ),
      )

      validator.validatedCreateOrganizationPostRequest(request).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("phoneNumbers", "Exactly one entry must be marked as default", List())
          )
        )
    }
  }
}
