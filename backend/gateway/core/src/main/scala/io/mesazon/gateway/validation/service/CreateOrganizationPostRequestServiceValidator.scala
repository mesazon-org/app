package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class CreateOrganizationPostRequestServiceValidator(
    emailValidator: EmailValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
) extends ServiceValidator[smithy.CreateOrganizationPostRequest, CreateOrganization] {

  override def domainValidator
      : DomainValidator[smithy.CreateOrganizationPostRequest, CreateOrganization] = { createOrganizationPostRequest =>
    validateOrganizationEmails(createOrganizationPostRequest.emails)
      .zip(validateOrganizationPhoneNumbers(createOrganizationPostRequest.phoneNumbers))
      .map((emailsValidated, phoneNumbersValidated) =>
        (
          validateRequiredField("name", createOrganizationPostRequest.name, OrganizationName.either),
          validateRequiredField("slug", createOrganizationPostRequest.slug, OrganizationSlug.either),
          validateOptionalField("tagline", createOrganizationPostRequest.tagline, OrganizationTagline.either),
          emailsValidated,
          phoneNumbersValidated,
          validateOptionalField(
            "addressLine1",
            createOrganizationPostRequest.addressLine1,
            OrganizationAddressLine1.either,
          ),
          validateOptionalField(
            "addressLine2",
            createOrganizationPostRequest.addressLine2,
            OrganizationAddressLine2.either,
          ),
          validateOptionalField("city", createOrganizationPostRequest.city, OrganizationCity.either),
          validateOptionalField("postalCode", createOrganizationPostRequest.postalCode, OrganizationPostalCode.either),
          validateOptionalField("country", createOrganizationPostRequest.country, OrganizationCountry.either),
          validateOptionalField(
            "companyRegistrationNumber",
            createOrganizationPostRequest.companyRegistrationNumber,
            OrganizationCompanyRegistrationNumber.either,
          ),
          validateOptionalField("taxID", createOrganizationPostRequest.taxID, OrganizationTaxID.either),
        ).mapN(CreateOrganization.apply)
      )
  }

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

object CreateOrganizationPostRequestServiceValidator {

  val live = ZLayer.derive[CreateOrganizationPostRequestServiceValidator]
}
