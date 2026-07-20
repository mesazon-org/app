package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class OrganizationManagementRequestValidator(
    emailValidator: EmailValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
) {

  def validatedCreateOrganizationPostRequest(
      request: smithy.CreateOrganizationPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CreateOrganization] =
    toValidatedRequestIO(validateCreateOrganization(request))

  private def validateCreateOrganization(
      request: smithy.CreateOrganizationPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CreateOrganization]] =
    validateOrganizationEmails(request.emails)
      .zip(validateOrganizationPhoneNumbers(request.phoneNumbers))
      .map((emailsValidated, phoneNumbersValidated) =>
        (
          validateRequiredField("name", request.name, OrganizationName.either),
          validateRequiredField("slug", request.slug, OrganizationSlug.either),
          validateOptionalField("tagline", request.tagline, OrganizationTagline.either),
          emailsValidated,
          phoneNumbersValidated,
          validateOptionalField("addressLine1", request.addressLine1, OrganizationAddressLine1.either),
          validateOptionalField("addressLine2", request.addressLine2, OrganizationAddressLine2.either),
          validateOptionalField("city", request.city, OrganizationCity.either),
          validateOptionalField("postalCode", request.postalCode, OrganizationPostalCode.either),
          validateOptionalField("country", request.country, OrganizationCountry.either),
          validateOptionalField(
            "companyRegistrationNumber",
            request.companyRegistrationNumber,
            OrganizationCompanyRegistrationNumber.either,
          ),
          validateOptionalField("taxID", request.taxID, OrganizationTaxID.either),
        ).mapN(CreateOrganization.apply)
      )

  private def validateOrganizationEmails(
      emails: List[smithy.OrganizationEmailRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[OrganizationEmailEntry]]] =
    validateAll(emails)(email =>
      emailValidator
        .validate(email.email, OrganizationEmail.either)
        .map(_.map(validated => OrganizationEmailEntry(validated, email.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("emails", entries)(_.isDefault)))

  private def validateOrganizationPhoneNumbers(
      phoneNumbers: List[smithy.OrganizationPhoneNumberRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[OrganizationPhoneNumberEntry]]] =
    validateAll(phoneNumbers)(phoneNumber =>
      phoneNumberDomainValidator
        .validate(phoneNumber.phoneNumber.phoneCountryCode, phoneNumber.phoneNumber.phoneNationalNumber)
        .map(
          _.map(validated => OrganizationPhoneNumberEntry(OrganizationPhoneNumber(validated), phoneNumber.isDefault))
        )
    ).map(_.andThen(entries => validateSingleDefault("phoneNumbers", entries)(_.isDefault)))
}

object OrganizationManagementRequestValidator {

  val live = ZLayer.derive[OrganizationManagementRequestValidator]
}
