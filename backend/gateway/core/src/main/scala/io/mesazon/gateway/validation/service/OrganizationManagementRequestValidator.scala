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
    addressValidator: AddressValidator,
) {

  def validatedCreateOrganizationPostRequest(
      request: smithy.CreateOrganizationPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CreateOrganizationPostRequest] =
    toValidatedRequestIO(validateCreateOrganization(request))

  private def validateCreateOrganization(
      request: smithy.CreateOrganizationPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CreateOrganizationPostRequest]] =
    validateOrganizationEmails(request.emails)
      .zip(validateOrganizationPhoneNumbers(request.phoneNumbers))
      .zip(validateOrganizationAddresses(request.addresses))
      .map((emailsValidated, phoneNumbersValidated, addressesValidated) =>
        (
          validateRequiredField("name", request.name, OrganizationName.either),
          validateRequiredField("slug", request.slug, OrganizationSlug.either),
          validateOptionalField("tagline", request.tagline, OrganizationTagline.either),
          emailsValidated,
          phoneNumbersValidated,
          addressesValidated,
          validateOptionalField(
            "companyRegistrationNumber",
            request.companyRegistrationNumber,
            OrganizationCompanyRegistrationNumber.either,
          ),
          validateOptionalField("taxID", request.taxID, OrganizationTaxID.either),
        ).mapN(CreateOrganizationPostRequest.apply)
      )

  private def validateOrganizationEmails(
      emails: List[smithy.OrganizationEmailEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[OrganizationEmailEntryRequest]]] =
    validateAll(emails)(email =>
      emailValidator
        .validate(email.email, OrganizationEmail.either)
        .map(_.map(validated => OrganizationEmailEntryRequest(validated, email.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("emails", entries)(_.isDefault)))

  private def validateOrganizationPhoneNumbers(
      phoneNumbers: List[smithy.OrganizationPhoneNumberEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[OrganizationPhoneNumberEntryRequest]]] =
    validateAll(phoneNumbers)(phoneNumber =>
      phoneNumberDomainValidator
        .validate(phoneNumber.phoneNumber.phoneCountryCode, phoneNumber.phoneNumber.phoneNationalNumber)
        .map(
          _.map(validated =>
            OrganizationPhoneNumberEntryRequest(OrganizationPhoneNumber(validated), phoneNumber.isDefault)
          )
        )
    ).map(_.andThen(entries => validateSingleDefault("phoneNumbers", entries)(_.isDefault)))

  private def validateOrganizationAddresses(
      addresses: List[smithy.AddressEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[OrganizationAddressEntry]]] =
    validateAll(addresses)(addressEntryRequest =>
      addressValidator.validate(addressEntryRequest).map(_.map(OrganizationAddressEntry(_)))
    )
}

object OrganizationManagementRequestValidator {

  val live = ZLayer.derive[OrganizationManagementRequestValidator]
}
