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
          (businessesValidated.toEither, individualsValidated.toEither).mapN(InsertCustomers.apply).toValidated
        )
    )

  def validatedUpdateCustomerIndividualPutRequest(
      request: smithy.UpdateCustomerIndividualPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, UpdateCustomerIndividual] =
    toValidatedRequestIO(
      validateOptionalCustomerEmail(request.email)
        .zip(validateOptionalCustomerPhoneNumber(request.phoneNumber))
        .map((emailValidated, phoneNumberValidated) =>
          (
            CustomerID(request.customerID).validNec,
            validateOptionalField("fullName", request.fullName, CustomerFullName.either),
            emailValidated,
            phoneNumberValidated,
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
      validateOptionalCustomerEmail(request.email)
        .zip(validateOptionalCustomerPhoneNumber(request.phoneNumber))
        .map((emailValidated, phoneNumberValidated) =>
          (
            CustomerID(request.customerID).validNec,
            validateOptionalField("businessName", request.businessName, CustomerBusinessName.either),
            emailValidated,
            validateOptionalField("taxID", request.taxID, CustomerTaxID.either),
            phoneNumberValidated,
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
      validateAllFailFast(request.customerBusinessContacts)(validateAddCustomerBusinessContact)
        .map(contactsValidated =>
          (CustomerID(request.customerID).validNec, contactsValidated).mapN(AddCustomerBusinessContacts.apply)
        )
    )

  /** Validates every item and fails fast: on the first invalid item the whole list is that item's error, so the caller
    * gets a single `ValidationError` for the first offender rather than errors accumulated across the list.
    */
  private def validateAllFailFast[A, B](
      items: List[A]
  )(validate: A => UIO[ValidatedNec[InvalidFieldError, B]]): UIO[ValidatedNec[InvalidFieldError, List[B]]] =
    ZIO.foreach(items)(validate).map(_.traverse(_.toEither).toValidated)

  private def validateOptionalCustomerEmail(
      emailRawOpt: Option[String]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CustomerEmail]]] =
    emailValidator.validateOptional(emailRawOpt, CustomerEmail.either)

  private def validateOptionalCustomerPhoneNumber(
      phoneNumberRawOpt: Option[smithy.PhoneNumberRequest]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CustomerPhoneNumber]]] =
    phoneNumberRawOpt match {
      case None => ZIO.succeed(none[CustomerPhoneNumber].validNec)
      case Some(phoneNumber) =>
        phoneNumberDomainValidator
          .validate(phoneNumber.phoneCountryCode, phoneNumber.phoneNationalNumber)
          .map(_.map(validated => CustomerPhoneNumber(validated).some))
    }

  private def validateInsertCustomerIndividual(
      request: smithy.InsertCustomerIndividualPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, InsertCustomerIndividual]] =
    validateOptionalCustomerEmail(request.email)
      .zip(validateOptionalCustomerPhoneNumber(request.phoneNumber))
      .map((emailValidated, phoneNumberValidated) =>
        (
          validateRequiredField("fullName", request.fullName, CustomerFullName.either),
          emailValidated,
          phoneNumberValidated,
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
    validateAllFailFast(requests)(validateInsertCustomerIndividual)

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
    validateOptionalCustomerEmail(request.email)
      .zip(validateOptionalCustomerPhoneNumber(request.phoneNumber))
      .zip(
        validateAllFailFast(request.customerBusinessContacts.getOrElse(List.empty))(validateInsertCustomerBusinessContact)
      )
      .map((emailValidated, phoneNumberValidated, contactsValidated) =>
        (
          validateRequiredField("businessName", request.businessName, CustomerBusinessName.either),
          emailValidated,
          validateOptionalField("taxID", request.taxID, CustomerTaxID.either),
          phoneNumberValidated,
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
    validateAllFailFast(requests)(validateInsertCustomerBusiness)
}

object CustomerBookRequestValidator {

  val live = ZLayer.derive[CustomerBookRequestValidator]
}
