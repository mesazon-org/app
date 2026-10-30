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

  private def validateCustomerEmailEntries(
      customerEmailEntriesSmithy: List[smithy.CustomerEmailEntry]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerEmailEntry]]] =
    validateAll(customerEmailEntriesSmithy)(customerEmailEntrySmithy =>
      emailValidator
        .validate(customerEmailEntrySmithy.email, CustomerEmail.either)
        .map(_.map(customerEmail => CustomerEmailEntry(customerEmail, customerEmailEntrySmithy.isDefault)))
    ).map(_.andThen(customerEmailEntries => validateSingleDefault("emails", customerEmailEntries)(_.isDefault)))

  private def validateCustomerPhoneNumberEntries(
      customerPhoneNumberEntriesSmithy: List[smithy.CustomerPhoneNumberEntry]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerPhoneNumberEntry]]] =
    validateAll(customerPhoneNumberEntriesSmithy)(customerPhoneNumberEntrySmithy =>
      phoneNumberDomainValidator
        .validate(
          customerPhoneNumberEntrySmithy.phoneNumber.phoneCountryCode,
          customerPhoneNumberEntrySmithy.phoneNumber.phoneNationalNumber,
        )
        .map(phoneNumberValidated =>
          phoneNumberValidated.map(phoneNumber =>
            CustomerPhoneNumberEntry(CustomerPhoneNumber(phoneNumber), customerPhoneNumberEntrySmithy.isDefault)
          )
        )
    ).map(
      _.andThen(customerPhoneNumberEntries =>
        validateSingleDefault("phoneNumbers", customerPhoneNumberEntries)(_.isDefault)
      )
    )

  private def validateCustomerAddressEntries(
      customerAddressEntriesSmithy: List[smithy.CustomerAddressEntry]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerAddressEntry]]] =
    addressValidator
      .validateAddressEntries(customerAddressEntriesSmithy.transformInto[List[smithy.AddressEntryRequest]])
      .map(_.map(_.map(CustomerAddressEntry(_))))

  private def validateOptionalCustomerEntries[A, B](
      entriesOpt: Option[List[A]]
  )(
      validateEntries: List[A] => UIO[ValidatedNec[InvalidFieldError, List[B]]]
  ): UIO[ValidatedNec[InvalidFieldError, Option[List[B]]]] =
    entriesOpt match {
      case None         => ZIO.succeed(none[List[B]].validNec)
      case Some(values) => validateEntries(values).map(_.map(_.some))
    }

  private def validateCustomerBookIndividual(
      customerBookIndividualPostRequestSmithy: smithy.CustomerBookIndividualPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookIndividualPostRequest]] =
    validateCustomerEmailEntries(customerBookIndividualPostRequestSmithy.emails)
      .zip(validateCustomerPhoneNumberEntries(customerBookIndividualPostRequestSmithy.phoneNumbers))
      .zip(validateCustomerAddressEntries(customerBookIndividualPostRequestSmithy.addresses))
      .map((emailsValidated, phoneNumbersValidated, addressesValidated) =>
        (
          validateRequiredField("fullName", customerBookIndividualPostRequestSmithy.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          addressesValidated,
        ).mapN(CustomerBookIndividualPostRequest.apply)
      )

  private def validateCustomerBookIndividualPut(
      customerBookIndividualPutRequestSmithy: smithy.CustomerBookIndividualPutRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookIndividualPutRequest]] =
    validateOptionalCustomerEntries(customerBookIndividualPutRequestSmithy.emails)(validateCustomerEmailEntries)
      .zip(
        validateOptionalCustomerEntries(customerBookIndividualPutRequestSmithy.phoneNumbers)(
          validateCustomerPhoneNumberEntries
        )
      )
      .zip(
        validateOptionalCustomerEntries(customerBookIndividualPutRequestSmithy.addresses)(
          validateCustomerAddressEntries
        )
      )
      .map((emailsValidated, phoneNumbersValidated, addressesValidated) =>
        (
          validateOptionalField("fullName", customerBookIndividualPutRequestSmithy.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          addressesValidated,
        ).mapN(CustomerBookIndividualPutRequest.apply)
      )

  private def validateNullableCustomerPhoneNumber(
      phoneNumberRequestNullableSmithyOpt: Option[Nullable[smithy.PhoneNumberRequest]]
  ): UIO[ValidatedNec[InvalidFieldError, Option[Option[CustomerPhoneNumber]]]] =
    phoneNumberRequestNullableSmithyOpt match {
      case None                                   => ZIO.succeed(none[Option[CustomerPhoneNumber]].validNec)
      case Some(phoneNumberRequestNullableSmithy) =>
        validateOptionalCustomerPhoneNumber(phoneNumberRequestNullableSmithy.toOption).map(_.map(_.some))
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
          emailValidated.map(customerEmailOpt => customerBusinessContactUpdateSmithy.email.map(_ => customerEmailOpt)),
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
      customerBusinessContactInsertSmithy: smithy.CustomerBusinessContactInsert
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBusinessContactInsert]] =
    validateOptionalCustomerEmail(customerBusinessContactInsertSmithy.email)
      .zip(validateOptionalCustomerPhoneNumber(customerBusinessContactInsertSmithy.phoneNumber))
      .map((emailValidated, phoneNumberValidated) =>
        (
          validateRequiredField("fullName", customerBusinessContactInsertSmithy.fullName, CustomerFullName.either),
          validateOptionalField("role", customerBusinessContactInsertSmithy.role, CustomerBusinessContactRole.either),
          emailValidated,
          phoneNumberValidated,
        ).mapN(CustomerBusinessContactInsert.apply)
      )

  private def validateCustomerBookBusiness(
      customerBookBusinessPostRequestSmithy: smithy.CustomerBookBusinessPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CustomerBookBusinessPostRequest]] =
    validateCustomerEmailEntries(customerBookBusinessPostRequestSmithy.emails)
      .zip(validateCustomerPhoneNumberEntries(customerBookBusinessPostRequestSmithy.phoneNumbers))
      .zip(validateCustomerAddressEntries(customerBookBusinessPostRequestSmithy.addresses))
      .zip(
        validateAll(customerBookBusinessPostRequestSmithy.customerBusinessContacts)(
          validateCustomerBusinessContactInsert
        )
      )
      .map((emailsValidated, phoneNumbersValidated, addressesValidated, contactsValidated) =>
        (
          validateRequiredField(
            "businessName",
            customerBookBusinessPostRequestSmithy.businessName,
            CustomerBusinessName.either,
          ),
          emailsValidated,
          validateOptionalField("taxID", customerBookBusinessPostRequestSmithy.taxID, CustomerTaxID.either),
          phoneNumbersValidated,
          addressesValidated,
          contactsValidated,
        ).mapN(CustomerBookBusinessPostRequest.apply)
      )

  private def validateCustomerBookIndividuals(
      customerBookIndividualPostRequestsSmithy: List[smithy.CustomerBookIndividualPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerBookIndividualPostRequest]]] =
    validateAllNested("customerIndividual", customerBookIndividualPostRequestsSmithy)(validateCustomerBookIndividual)

  private def validateCustomerBookBusinesses(
      customerBookBusinessPostRequestsSmithy: List[smithy.CustomerBookBusinessPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerBookBusinessPostRequest]]] =
    validateAllNested("customerBusiness", customerBookBusinessPostRequestsSmithy)(validateCustomerBookBusiness)

  private def validateOptionalCustomerEmail(
      emailRawOpt: Option[String]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CustomerEmail]]] =
    emailValidator.validateOptional(emailRawOpt, CustomerEmail.either)

  private def validateOptionalCustomerPhoneNumber(
      phoneNumberRequestSmithyOpt: Option[smithy.PhoneNumberRequest]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CustomerPhoneNumber]]] =
    phoneNumberRequestSmithyOpt match {
      case None                           => ZIO.succeed(none[CustomerPhoneNumber].validNec)
      case Some(phoneNumberRequestSmithy) =>
        phoneNumberDomainValidator
          .validate(phoneNumberRequestSmithy.phoneCountryCode, phoneNumberRequestSmithy.phoneNationalNumber)
          .map(_.map(phoneNumber => CustomerPhoneNumber(phoneNumber).some))
    }

  def validatedCustomerBookIndividualPostRequest(
      customerBookIndividualPostRequestSmithy: smithy.CustomerBookIndividualPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookIndividualPostRequest] =
    toValidatedRequestIO(validateCustomerBookIndividual(customerBookIndividualPostRequestSmithy))

  def validatedCustomerBookBusinessPostRequest(
      customerBookBusinessPostRequestSmithy: smithy.CustomerBookBusinessPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookBusinessPostRequest] =
    toValidatedRequestIO(validateCustomerBookBusiness(customerBookBusinessPostRequestSmithy))

  def validatedCustomerBookBatchPostRequest(
      customerBookBatchPostRequestSmithy: smithy.CustomerBookBatchPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookBatchPostRequest] =
    toValidatedRequestIO(
      validateCustomerBookBusinesses(customerBookBatchPostRequestSmithy.customerBusinesses)
        .zip(validateCustomerBookIndividuals(customerBookBatchPostRequestSmithy.customerIndividuals))
        .map((businessesValidated, individualsValidated) =>
          (businessesValidated, individualsValidated).mapN(CustomerBookBatchPostRequest.apply)
        )
    )

  def validatedCustomerBookIndividualPutRequest(
      customerBookIndividualPutRequestSmithy: smithy.CustomerBookIndividualPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CustomerBookIndividualPutRequest] =
    toValidatedRequestIO(validateCustomerBookIndividualPut(customerBookIndividualPutRequestSmithy))

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

}

object CustomerBookRequestValidator {

  val live = ZLayer.derive[CustomerBookRequestValidator]
}
