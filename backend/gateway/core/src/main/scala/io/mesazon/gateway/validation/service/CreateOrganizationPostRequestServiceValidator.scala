package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.ZLayer

final class CreateOrganizationPostRequestServiceValidator(
    emailDomainValidator: EmailDomainValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
) extends ServiceValidator[smithy.CreateOrganizationPostRequest, CreateOrganization] {

  override def domainValidator
      : DomainValidator[smithy.CreateOrganizationPostRequest, CreateOrganization] = { createOrganizationPostRequest =>
    emailDomainValidator
      .validate(createOrganizationPostRequest.email)
      .zip(
        phoneNumberDomainValidator
          .validate(
            createOrganizationPostRequest.phoneNumber.phoneCountryCode,
            createOrganizationPostRequest.phoneNumber.phoneNationalNumber,
          )
      )
      .map((emailValidated, phoneNumberValidated) =>
        (
          validateRequiredField("name", createOrganizationPostRequest.name, OrganizationName.either),
          validateRequiredField("slug", createOrganizationPostRequest.slug, OrganizationSlug.either),
          emailValidated.map(email => OrganizationEmail.assume(email.value)),
          phoneNumberValidated.map(phoneNumber => OrganizationPhoneNumber(phoneNumber)),
          validateRequiredField(
            "addressLine1",
            createOrganizationPostRequest.addressLine1,
            OrganizationAddressLine1.either,
          ),
          validateOptionalField(
            "addressLine2",
            createOrganizationPostRequest.addressLine2,
            OrganizationAddressLine2.either,
          ),
          validateRequiredField("city", createOrganizationPostRequest.city, OrganizationCity.either),
          validateRequiredField("postalCode", createOrganizationPostRequest.postalCode, OrganizationPostalCode.either),
          validateRequiredField("country", createOrganizationPostRequest.country, OrganizationCountry.either),
        ).mapN(CreateOrganization.apply)
      )
  }
}

object CreateOrganizationPostRequestServiceValidator {

  val live = ZLayer.derive[CreateOrganizationPostRequestServiceValidator]
}
