package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import io.scalaland.chimney.dsl.*
import smithy4s.Nullable
import zio.*

final class CustomerBookRequestValidator(
    emailValidator: EmailValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
    addressValidator: AddressValidator,
) {

  def validatedCustomerBookIndividualPostRequest(
      request: smithy.CustomerBookIndividualPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookIndividualPostRequest] =
    toValidatedRequestIO(validateCustomerBookIndividual(request))

  def validatedCustomerBookBusinessPostRequest(
      request: smithy.CustomerBookBusinessPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookBusinessPostRequest] =
    toValidatedRequestIO(validateCustomerBookBusiness(request))

  def validatedCustomerBookBatchPostRequest(
      request: smithy.CustomerBookBatchPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookBatchPostRequest] =
    toValidatedRequestIO(
      validateCustomerBookBusinesses(request.customerBusinesses)
        .zip(validateCustomerBookIndividuals(request.customerIndividuals))
        .map((businessesValidated, individualsValidated) =>
          (businessesValidated, individualsValidated).mapN(CustomerBookBatchPostRequest.apply)
        )
    )

  def validatedCustomerBookIndividualPutRequest(
      request: smithy.CustomerBookIndividualPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookIndividualPutRequest] =
    toValidatedRequestIO(validateCustomerBookIndividualPut(request))

  def validatedCustomerBookBusinessPutRequest(
      customerBookBusinessPutRequestSmithy: smithy.CustomerBookBusinessPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookBusinessPutRequest] =
    toValidatedRequestIO(validateCustomerBookBusinessPut(customerBookBusinessPutRequestSmithy))

  def validatedCustomerBookUniquenessGetRequest(
      customerBookUniquenessGetInputSmithy: smithy.CustomerBookUniquenessGetInput
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookUniquenessGetRequest] =
    toValidatedRequestIO(
      ZIO.succeed(
        (
          customerBookUniquenessGetInputSmithy.name
            .toValidNec(InvalidFieldError("name", "Name must be provided", Seq.empty))
            .andThen(name => validateRequiredField("name", name, CustomerName.either)),
          customerBookUniquenessGetInputSmithy.customerType
            .toValidNec(InvalidFieldError("customerType", "Customer type must be provided", Seq.empty))
            .map(_.transformInto[CustomerType]),
          customerBookUniquenessGetInputSmithy.customerID.map(CustomerID.apply).validNec,
        ).mapN(CustomerBookUniquenessGetRequest.apply)
      )
    )

  private def validateCustomerEmailEntries(
      emails: List[smithy.CustomerEmailEntry]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerEmailEntry]]] =
    validateAll(emails)(customerEmailEntry =>
      emailValidator
        .validate(customerEmailEntry.email, CustomerEmail.either)
        .map(_.map(emailValidated => CustomerEmailEntry(emailValidated, customerEmailEntry.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("emails", entries)(_.isDefault)))

  private def validateCustomerPhoneNumberEntries(
      phoneNumbers: List[smithy.CustomerPhoneNumberEntry]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerPhoneNumberEntry]]] =
    validateAll(phoneNumbers)(customerPhoneNumberEntry =>
      phoneNumberDomainValidator
        .validate(
          customerPhoneNumberEntry.phoneNumber.phoneCountryCode,
          customerPhoneNumberEntry.phoneNumber.phoneNationalNumber,
        )
        .map(phoneNumberValidated =>
          phoneNumberValidated.map(phoneNumber =>
            CustomerPhoneNumberEntry(CustomerPhoneNumber(phoneNumber), customerPhoneNumberEntry.isDefault)
          )
        )
    ).map(_.andThen(entries => validateSingleDefault("phoneNumbers", entries)(_.isDefault)))

  private def validateCustomerAddressEntries(
      addresses: List[smithy.CustomerAddressEntry]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerAddressEntry]]] =
    addressValidator
      .validateAddressEntries(addresses.transformInto[List[smithy.AddressEntryRequest]])
      .map(_.map(_.map(CustomerAddressEntry(_))))

  private def validateOptionalCustomerEntries[A, B](
      entries: Option[List[A]]
  )(
      validateEntries: List[A] => UIO[ValidatedNec[InvalidFieldError, List[B]]]
  ): UIO[ValidatedNec[InvalidFieldError, Option[List[B]]]] =
    entries match {
      case None         => ZIO.succeed(none[List[B]].validNec)
      case Some(values) => validateEntries(values).map(_.map(_.some))
    }

  private def validateCustomerBookIndividual(
      request: smithy.CustomerBookIndividualPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookIndividualPostRequest]] =
    validateCustomerEmailEntries(request.emails)
      .zip(validateCustomerPhoneNumberEntries(request.phoneNumbers))
      .zip(validateCustomerAddressEntries(request.addresses))
      .map((emailsValidated, phoneNumbersValidated, addressesValidated) =>
        (
          validateRequiredField("fullName", request.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          addressesValidated,
        ).mapN(CustomerBookIndividualPostRequest.apply)
      )

  private def validateCustomerBookIndividualPut(
      request: smithy.CustomerBookIndividualPutRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookIndividualPutRequest]] =
    validateOptionalCustomerEntries(request.emails)(validateCustomerEmailEntries)
      .zip(validateOptionalCustomerEntries(request.phoneNumbers)(validateCustomerPhoneNumberEntries))
      .zip(validateOptionalCustomerEntries(request.addresses)(validateCustomerAddressEntries))
      .map((emailsValidated, phoneNumbersValidated, addressesValidated) =>
        (
          validateOptionalField("fullName", request.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          addressesValidated,
        ).mapN(CustomerBookIndividualPutRequest.apply)
      )

  private def validateNullableCustomerPhoneNumber(
      phoneNumberRawOpt: Option[Nullable[smithy.PhoneNumberRequest]]
  ): UIO[ValidatedNec[InvalidFieldError, Option[Option[CustomerPhoneNumber]]]] =
    phoneNumberRawOpt match {
      case None              => ZIO.succeed(none[Option[CustomerPhoneNumber]].validNec)
      case Some(phoneNumber) => validateOptionalCustomerPhoneNumber(phoneNumber.toOption).map(_.map(_.some))
    }

  private def validateCustomerBusinessContactUpdate(
      customerBusinessContactUpdateSmithy: smithy.CustomerBusinessContactUpdate
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBusinessContactUpdate]] =
    validateOptionalCustomerEmail(customerBusinessContactUpdateSmithy.email.flatMap(_.toOption))
      .zip(validateNullableCustomerPhoneNumber(customerBusinessContactUpdateSmithy.phoneNumber))
      .map((emailValidated, phoneNumberValidated) =>
        (
          CustomerBusinessContactID(customerBusinessContactUpdateSmithy.customerBusinessContactID).validNec,
          validateOptionalField("fullName", customerBusinessContactUpdateSmithy.fullName, CustomerFullName.either),
          validateNullableField("role", customerBusinessContactUpdateSmithy.role, CustomerBusinessContactRole.either),
          emailValidated.map(email => customerBusinessContactUpdateSmithy.email.map(_ => email)),
          phoneNumberValidated,
        ).mapN(CustomerBusinessContactUpdate.apply)
      )

  private def validateCustomerBusinessContactIDs(
      fieldName: String,
      customerBusinessContactIDs: List[CustomerBusinessContactID],
  ): ValidatedNec[InvalidFieldError, Unit] =
    customerBusinessContactIDs.zipWithIndex.traverse { case (customerBusinessContactID, index) =>
      if (customerBusinessContactIDs.take(index).contains(customerBusinessContactID))
        InvalidFieldError(
          fieldName,
          "Contact ID must be unique within this list",
          List(customerBusinessContactID.value.toString),
          index = index,
        ).invalidNec
      else ().validNec
    }.void

  private def validateCustomerBusinessContactArchives(
      customerBusinessContactsArchiveSmithy: List[smithy.CustomerBusinessContactArchive],
      customerBusinessContactIDsUpdate: Set[CustomerBusinessContactID],
  ): ValidatedNec[InvalidFieldError, List[CustomerBusinessContactArchive]] = {
    val customerBusinessContactsArchive =
      customerBusinessContactsArchiveSmithy.map(customerBusinessContactArchiveSmithy =>
        CustomerBusinessContactArchive(
          CustomerBusinessContactID(customerBusinessContactArchiveSmithy.customerBusinessContactID)
        )
      )
    val customerBusinessContactIDsArchive = customerBusinessContactsArchive.map(_.customerBusinessContactID)
    val overlapsValidated                 = customerBusinessContactIDsArchive.zipWithIndex.traverse {
      case (customerBusinessContactID, index) =>
        if (customerBusinessContactIDsUpdate.contains(customerBusinessContactID))
          InvalidFieldError(
            "customerBusinessContactsArchive",
            "Contact ID must not also appear in customerBusinessContactsUpdate",
            List(customerBusinessContactID.value.toString),
            index = index,
          ).invalidNec
        else ().validNec
    }
    (
      customerBusinessContactsArchive.validNec[InvalidFieldError],
      validateCustomerBusinessContactIDs("customerBusinessContactsArchive", customerBusinessContactIDsArchive),
      overlapsValidated,
    ).mapN((customerBusinessContactsArchive, _, _) => customerBusinessContactsArchive)
  }

  private def validateCustomerBookBusinessPut(
      customerBookBusinessPutRequestSmithy: smithy.CustomerBookBusinessPutRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookBusinessPutRequest]] = {
    val customerBusinessContactIDsUpdate = customerBookBusinessPutRequestSmithy.customerBusinessContactsUpdate
      .map(customerBusinessContactUpdateSmithy =>
        CustomerBusinessContactID(customerBusinessContactUpdateSmithy.customerBusinessContactID)
      )

    validateOptionalCustomerEntries(customerBookBusinessPutRequestSmithy.emails)(validateCustomerEmailEntries)
      .zip(
        validateOptionalCustomerEntries(customerBookBusinessPutRequestSmithy.phoneNumbers)(
          validateCustomerPhoneNumberEntries
        )
      )
      .zip(
        validateOptionalCustomerEntries(customerBookBusinessPutRequestSmithy.addresses)(validateCustomerAddressEntries)
      )
      .zip(
        validateAllNested(
          "customerBusinessContactInsert",
          customerBookBusinessPutRequestSmithy.customerBusinessContactsInsert,
        )(validateCustomerBusinessContactInsert)
      )
      .zip(
        validateAllNested(
          "customerBusinessContactUpdate",
          customerBookBusinessPutRequestSmithy.customerBusinessContactsUpdate,
        )(validateCustomerBusinessContactUpdate)
      )
      .map(
        (
            emailsValidated,
            phoneNumbersValidated,
            addressesValidated,
            contactsInsertValidated,
            contactsUpdateValidated,
        ) =>
          (
            validateOptionalField(
              "businessName",
              customerBookBusinessPutRequestSmithy.businessName,
              CustomerBusinessName.either,
            ),
            emailsValidated,
            validateNullableField("taxID", customerBookBusinessPutRequestSmithy.taxID, CustomerTaxID.either),
            phoneNumbersValidated,
            addressesValidated,
            contactsInsertValidated,
            (
              contactsUpdateValidated,
              validateCustomerBusinessContactIDs("customerBusinessContactsUpdate", customerBusinessContactIDsUpdate),
            )
              .mapN((contactsUpdate, _) => contactsUpdate),
            validateCustomerBusinessContactArchives(
              customerBookBusinessPutRequestSmithy.customerBusinessContactsArchive,
              customerBusinessContactIDsUpdate.toSet,
            ),
          ).mapN(CustomerBookBusinessPutRequest.apply)
      )
  }

  private def validateCustomerBusinessContactInsert(
      contact: smithy.CustomerBusinessContactInsert
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBusinessContactInsert]] =
    validateOptionalCustomerEmail(contact.email)
      .zip(validateOptionalCustomerPhoneNumber(contact.phoneNumber))
      .map((emailValidated, phoneNumberValidated) =>
        (
          validateRequiredField("fullName", contact.fullName, CustomerFullName.either),
          validateOptionalField("role", contact.role, CustomerBusinessContactRole.either),
          emailValidated,
          phoneNumberValidated,
        ).mapN(CustomerBusinessContactInsert.apply)
      )

  private def validateCustomerBookBusiness(
      request: smithy.CustomerBookBusinessPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookBusinessPostRequest]] =
    validateCustomerEmailEntries(request.emails)
      .zip(validateCustomerPhoneNumberEntries(request.phoneNumbers))
      .zip(validateCustomerAddressEntries(request.addresses))
      .zip(validateAll(request.customerBusinessContacts)(validateCustomerBusinessContactInsert))
      .map((emailsValidated, phoneNumbersValidated, addressesValidated, contactsValidated) =>
        (
          validateRequiredField("businessName", request.businessName, CustomerBusinessName.either),
          emailsValidated,
          validateOptionalField("taxID", request.taxID, CustomerTaxID.either),
          phoneNumbersValidated,
          addressesValidated,
          contactsValidated,
        ).mapN(CustomerBookBusinessPostRequest.apply)
      )

  private def validateCustomerBookIndividuals(
      requests: List[smithy.CustomerBookIndividualPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerBookIndividualPostRequest]]] =
    validateAllNested("customerIndividual", requests)(validateCustomerBookIndividual)

  private def validateCustomerBookBusinesses(
      requests: List[smithy.CustomerBookBusinessPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerBookBusinessPostRequest]]] =
    validateAllNested("customerBusiness", requests)(validateCustomerBookBusiness)

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

}

object CustomerBookRequestValidator {

  val live = ZLayer.derive[CustomerBookRequestValidator]
}
