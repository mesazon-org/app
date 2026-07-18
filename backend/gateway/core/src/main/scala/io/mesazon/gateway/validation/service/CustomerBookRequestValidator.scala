package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class CustomerBookRequestValidator(
    emailValidator: EmailValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
) {

  def validatedInsertCustomerIndividualPostRequest(
      request: smithy.InsertCustomerIndividualPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerIndividual] =
    toValidatedRequestIO(validateInsertCustomerIndividual(request))

  def validatedInsertCustomerIndividualsPostRequest(
      request: smithy.InsertCustomerIndividualsPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerIndividuals] =
    toValidatedRequestIO(
      validateInsertCustomerIndividuals(request.customerIndividuals).map(_.map(InsertCustomerIndividuals.apply))
    )

  def validatedInsertCustomerBusinessPostRequest(
      request: smithy.InsertCustomerBusinessPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerBusiness] =
    toValidatedRequestIO(validateInsertCustomerBusiness(request))

  def validatedInsertCustomerBusinessesPostRequest(
      request: smithy.InsertCustomerBusinessesPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerBusinesses] =
    toValidatedRequestIO(
      validateInsertCustomerBusinesses(request.customerBusinesses).map(_.map(InsertCustomerBusinesses.apply))
    )

  def validatedInsertCustomersPostRequest(
      request: smithy.InsertCustomersPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomers] =
    toValidatedRequestIO(
      validateInsertCustomerBusinesses(request.customerBusinesses)
        .zip(validateInsertCustomerIndividuals(request.customerIndividuals))
        .map((businessesValidated, individualsValidated) =>
          (businessesValidated, individualsValidated).mapN(InsertCustomers.apply)
        )
    )

  def validatedUpdateCustomerIndividualPutRequest(
      request: smithy.UpdateCustomerIndividualPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, UpdateCustomerIndividual] =
    toValidatedRequestIO(
      validateCustomerEmails(request.emails)
        .zip(validateCustomerPhoneNumbers(request.phoneNumbers))
        .map((emailsValidated, phoneNumbersValidated) =>
          (
            CustomerID(request.customerID).validNec,
            validateOptionalField("fullName", request.fullName, CustomerFullName.either),
            emailsValidated,
            phoneNumbersValidated,
            validateOptionalField("addressLine1", request.addressLine1, CustomerAddressLine1.either),
            validateOptionalField("addressLine2", request.addressLine2, CustomerAddressLine2.either),
            validateOptionalField("city", request.city, CustomerCity.either),
            validateOptionalField("postalCode", request.postalCode, CustomerPostalCode.either),
            validateOptionalField("country", request.country, CustomerCountry.either),
          ).mapN(UpdateCustomerIndividual.apply)
        )
    )

  def validatedUpdateCustomerBusinessPutRequest(
      request: smithy.UpdateCustomerBusinessPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, UpdateCustomerBusiness] =
    toValidatedRequestIO(
      validateCustomerEmails(request.emails)
        .zip(validateCustomerPhoneNumbers(request.phoneNumbers))
        .map((emailsValidated, phoneNumbersValidated) =>
          (
            CustomerID(request.customerID).validNec,
            validateOptionalField("businessName", request.businessName, CustomerBusinessName.either),
            emailsValidated,
            validateOptionalField("taxID", request.taxID, CustomerTaxID.either),
            phoneNumbersValidated,
            validateOptionalField("addressLine1", request.addressLine1, CustomerAddressLine1.either),
            validateOptionalField("addressLine2", request.addressLine2, CustomerAddressLine2.either),
            validateOptionalField("city", request.city, CustomerCity.either),
            validateOptionalField("postalCode", request.postalCode, CustomerPostalCode.either),
            validateOptionalField("country", request.country, CustomerCountry.either),
          ).mapN(UpdateCustomerBusiness.apply)
        )
    )

  def validatedAddCustomerBusinessContactsPutRequest(
      request: smithy.AddCustomerBusinessContactsPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, AddCustomerBusinessContacts] =
    toValidatedRequestIO(
      validateAll(request.customerBusinessContacts)(validateAddCustomerBusinessContact)
        .map(contactsValidated =>
          (CustomerID(request.customerID).validNec, contactsValidated).mapN(AddCustomerBusinessContacts.apply)
        )
    )

  private def validateCustomerEmails(
      emails: List[smithy.CustomerEmailRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerEmailEntry]]] =
    validateAll(emails)(email =>
      emailValidator
        .validate(email.email, CustomerEmail.either)
        .map(_.map(validated => CustomerEmailEntry(validated, email.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("emails", entries)(_.isDefault)))

  private def validateCustomerPhoneNumbers(
      phoneNumbers: List[smithy.CustomerPhoneNumberRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerPhoneNumberEntry]]] =
    validateAll(phoneNumbers)(phoneNumber =>
      phoneNumberDomainValidator
        .validate(phoneNumber.phoneNumber.phoneCountryCode, phoneNumber.phoneNumber.phoneNationalNumber)
        .map(_.map(validated => CustomerPhoneNumberEntry(CustomerPhoneNumber(validated), phoneNumber.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("phoneNumbers", entries)(_.isDefault)))

  private def validateOptionalCustomerEmail(
      emailRawOpt: Option[String]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CustomerEmail]]] =
    emailValidator.validateOptional(emailRawOpt, CustomerEmail.either)

  private def validateOptionalCustomerPhoneNumber(
      phoneNumberRawOpt: Option[smithy.PhoneNumberRequest]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CustomerPhoneNumber]]] =
    phoneNumberRawOpt match {
      case None              => ZIO.succeed(none[CustomerPhoneNumber].validNec)
      case Some(phoneNumber) =>
        phoneNumberDomainValidator
          .validate(phoneNumber.phoneCountryCode, phoneNumber.phoneNationalNumber)
          .map(_.map(validated => CustomerPhoneNumber(validated).some))
    }

  private def validateInsertCustomerIndividual(
      request: smithy.InsertCustomerIndividualPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, InsertCustomerIndividual]] =
    validateCustomerEmails(request.emails)
      .zip(validateCustomerPhoneNumbers(request.phoneNumbers))
      .map((emailsValidated, phoneNumbersValidated) =>
        (
          validateRequiredField("fullName", request.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          validateOptionalField("addressLine1", request.addressLine1, CustomerAddressLine1.either),
          validateOptionalField("addressLine2", request.addressLine2, CustomerAddressLine2.either),
          validateOptionalField("city", request.city, CustomerCity.either),
          validateOptionalField("postalCode", request.postalCode, CustomerPostalCode.either),
          validateOptionalField("country", request.country, CustomerCountry.either),
        ).mapN(InsertCustomerIndividual.apply)
      )

  private def validateInsertCustomerIndividuals(
      requests: List[smithy.InsertCustomerIndividualPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[InsertCustomerIndividual]]] =
    validateAllNested("customerIndividuals", requests)(validateInsertCustomerIndividual)

  private def validateInsertCustomerBusinessContact(
      contact: smithy.InsertCustomerBusinessContact
  ): UIO[ValidatedNec[InvalidFieldError, InsertCustomerBusinessContact]] =
    validateOptionalCustomerEmail(contact.email)
      .zip(validateOptionalCustomerPhoneNumber(contact.phoneNumber))
      .map((emailValidated, phoneNumberValidated) =>
        (
          validateRequiredField("fullName", contact.fullName, CustomerFullName.either),
          validateOptionalField("role", contact.role, CustomerBusinessContactRole.either),
          emailValidated,
          phoneNumberValidated,
        ).mapN(InsertCustomerBusinessContact.apply)
      )

  private def validateAddCustomerBusinessContact(
      contact: smithy.AddCustomerBusinessContact
  ): UIO[ValidatedNec[InvalidFieldError, AddCustomerBusinessContact]] =
    validateOptionalCustomerEmail(contact.email)
      .zip(validateOptionalCustomerPhoneNumber(contact.phoneNumber))
      .map((emailValidated, phoneNumberValidated) =>
        (
          validateRequiredField("fullName", contact.fullName, CustomerFullName.either),
          validateOptionalField("role", contact.role, CustomerBusinessContactRole.either),
          emailValidated,
          phoneNumberValidated,
        ).mapN(AddCustomerBusinessContact.apply)
      )

  private def validateInsertCustomerBusiness(
      request: smithy.InsertCustomerBusinessPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, InsertCustomerBusiness]] =
    validateCustomerEmails(request.emails)
      .zip(validateCustomerPhoneNumbers(request.phoneNumbers))
      .zip(
        validateAll(request.customerBusinessContacts.getOrElse(List.empty))(
          validateInsertCustomerBusinessContact
        )
      )
      .map((emailsValidated, phoneNumbersValidated, contactsValidated) =>
        (
          validateRequiredField("businessName", request.businessName, CustomerBusinessName.either),
          emailsValidated,
          validateOptionalField("taxID", request.taxID, CustomerTaxID.either),
          phoneNumbersValidated,
          validateOptionalField("addressLine1", request.addressLine1, CustomerAddressLine1.either),
          validateOptionalField("addressLine2", request.addressLine2, CustomerAddressLine2.either),
          validateOptionalField("city", request.city, CustomerCity.either),
          validateOptionalField("postalCode", request.postalCode, CustomerPostalCode.either),
          validateOptionalField("country", request.country, CustomerCountry.either),
          contactsValidated,
        ).mapN(InsertCustomerBusiness.apply)
      )

  private def validateInsertCustomerBusinesses(
      requests: List[smithy.InsertCustomerBusinessPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[InsertCustomerBusiness]]] =
    validateAllNested("customerBusinesses", requests)(validateInsertCustomerBusiness)
}

object CustomerBookRequestValidator {

  val live = ZLayer.derive[CustomerBookRequestValidator]
}
