package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CustomerBookRepository
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.validation.service.CustomerBookRequestValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.scalaland.chimney.dsl.*
import zio.*

import java.util.UUID

object CustomerBookService {

  private final class CustomerBookServiceImpl(
      customerBookRequestValidator: CustomerBookRequestValidator,
      customerBookRepository: CustomerBookRepository,
  ) extends smithy.CustomerBookService[ServiceTask] {

    /** HTTP POST /insert/customer-individual */
    override def insertCustomerIndividualPost(
        organizationID: UUID,
        insertCustomerIndividualPostRequestSmithy: smithy.InsertCustomerIndividualPostRequest,
    ): ServiceTask[Unit] = for {
      insertCustomerIndividualPostRequest <- customerBookRequestValidator.validatedInsertCustomerIndividualPostRequest(
        insertCustomerIndividualPostRequestSmithy
      )
      _ <- customerBookRepository.insertCustomerIndividual(
        OrganizationID(organizationID),
        insertCustomerIndividualPostRequest.transformInto[InsertCustomerIndividualInput],
      )
    } yield ()

    /** HTTP POST /insert/customer-individuals */
    override def insertCustomerIndividualsPost(
        organizationID: UUID,
        insertCustomerIndividualsPostRequestSmithy: smithy.InsertCustomerIndividualsPostRequest,
    ): ServiceTask[Unit] = for {
      insertCustomerIndividualsPostRequest <-
        customerBookRequestValidator.validatedInsertCustomerIndividualsPostRequest(
          insertCustomerIndividualsPostRequestSmithy
        )
      _ <- customerBookRepository.insertCustomerIndividuals(
        OrganizationID(organizationID),
        insertCustomerIndividualsPostRequest.customerIndividuals.map(_.transformInto[InsertCustomerIndividualInput]),
      )
    } yield ()

    /** HTTP POST /insert/customer-business */
    override def insertCustomerBusinessPost(
        organizationID: UUID,
        insertCustomerBusinessPostRequestSmithy: smithy.InsertCustomerBusinessPostRequest,
    ): ServiceTask[Unit] = for {
      insertCustomerBusinessPostRequest <- customerBookRequestValidator.validatedInsertCustomerBusinessPostRequest(
        insertCustomerBusinessPostRequestSmithy
      )
      _ <- customerBookRepository.insertCustomerBusiness(
        OrganizationID(organizationID),
        insertCustomerBusinessPostRequest.transformInto[InsertCustomerBusinessInput],
      )
    } yield ()

    /** HTTP POST /insert/customer-businesses */
    override def insertCustomerBusinessesPost(
        organizationID: UUID,
        insertCustomerBusinessesPostRequestSmithy: smithy.InsertCustomerBusinessesPostRequest,
    ): ServiceTask[Unit] = for {
      insertCustomerBusinessesPostRequest <- customerBookRequestValidator.validatedInsertCustomerBusinessesPostRequest(
        insertCustomerBusinessesPostRequestSmithy
      )
      _ <- customerBookRepository.insertCustomerBusinesses(
        OrganizationID(organizationID),
        insertCustomerBusinessesPostRequest.customerBusinesses.map(_.transformInto[InsertCustomerBusinessInput]),
      )
    } yield ()

    /** HTTP POST /insert/customers */
    override def insertCustomersPost(
        organizationID: UUID,
        insertCustomersPostRequestSmithy: smithy.InsertCustomersPostRequest,
    ): ServiceTask[Unit] = for {
      insertCustomersPostRequest <- customerBookRequestValidator.validatedInsertCustomersPostRequest(
        insertCustomersPostRequestSmithy
      )
      _ <- customerBookRepository.insertCustomers(
        OrganizationID(organizationID),
        insertCustomersPostRequest.customerIndividuals.map(_.transformInto[InsertCustomerIndividualInput]),
        insertCustomersPostRequest.customerBusinesses.map(_.transformInto[InsertCustomerBusinessInput]),
      )
    } yield ()

    /** HTTP PUT /update/customer-individual */
    override def updateCustomerIndividualPut(
        organizationID: UUID,
        updateCustomerIndividualPutRequestSmithy: smithy.UpdateCustomerIndividualPutRequest,
    ): ServiceTask[Unit] = for {
      updateCustomerIndividualPutRequest <- customerBookRequestValidator.validatedUpdateCustomerIndividualPutRequest(
        updateCustomerIndividualPutRequestSmithy
      )
      _ <- customerBookRepository.updateCustomerIndividual(
        organizationID = OrganizationID(organizationID),
        customerID = updateCustomerIndividualPutRequest.customerID,
        fullNameOptUpdate = updateCustomerIndividualPutRequest.fullName,
        emailsOptUpdate = Some(updateCustomerIndividualPutRequest.emails.map(_.transformInto[CustomerEmailEntryInput])),
        phoneNumbersOptUpdate =
          Some(updateCustomerIndividualPutRequest.phoneNumbers.map(_.transformInto[CustomerPhoneNumberEntryInput])),
        addressLine1OptUpdate = updateCustomerIndividualPutRequest.addressLine1,
        addressLine2OptUpdate = updateCustomerIndividualPutRequest.addressLine2,
        cityOptUpdate = updateCustomerIndividualPutRequest.city,
        postalCodeOptUpdate = updateCustomerIndividualPutRequest.postalCode,
        countryOptUpdate = updateCustomerIndividualPutRequest.country,
      )
    } yield ()

    /** HTTP PUT /update/customer-business */
    override def updateCustomerBusinessPut(
        organizationID: UUID,
        updateCustomerBusinessPutRequestSmithy: smithy.UpdateCustomerBusinessPutRequest,
    ): ServiceTask[Unit] = for {
      updateCustomerBusinessPutRequest <- customerBookRequestValidator.validatedUpdateCustomerBusinessPutRequest(
        updateCustomerBusinessPutRequestSmithy
      )
      _ <- customerBookRepository.updateCustomerBusiness(
        organizationID = OrganizationID(organizationID),
        customerID = updateCustomerBusinessPutRequest.customerID,
        businessNameOptUpdate = updateCustomerBusinessPutRequest.businessName,
        emailsOptUpdate = Some(updateCustomerBusinessPutRequest.emails.map(_.transformInto[CustomerEmailEntryInput])),
        taxIDOptUpdate = updateCustomerBusinessPutRequest.taxID,
        phoneNumbersOptUpdate =
          Some(updateCustomerBusinessPutRequest.phoneNumbers.map(_.transformInto[CustomerPhoneNumberEntryInput])),
        addressLine1OptUpdate = updateCustomerBusinessPutRequest.addressLine1,
        addressLine2OptUpdate = updateCustomerBusinessPutRequest.addressLine2,
        cityOptUpdate = updateCustomerBusinessPutRequest.city,
        postalCodeOptUpdate = updateCustomerBusinessPutRequest.postalCode,
        countryOptUpdate = updateCustomerBusinessPutRequest.country,
      )
    } yield ()

    /** HTTP PUT /add/customer-business-contacts */
    override def addCustomerBusinessContactsPut(
        organizationID: UUID,
        addCustomerBusinessContactsPutRequestSmithy: smithy.AddCustomerBusinessContactsPutRequest,
    ): ServiceTask[Unit] = for {
      addCustomerBusinessContactsPutRequest <-
        customerBookRequestValidator.validatedAddCustomerBusinessContactsPutRequest(
          addCustomerBusinessContactsPutRequestSmithy
        )
      _ <- customerBookRepository.addCustomerBusinessContacts(
        OrganizationID(organizationID),
        addCustomerBusinessContactsPutRequest.customerID,
        addCustomerBusinessContactsPutRequest.customerBusinessContacts
          .map(_.transformInto[CustomerBusinessContactInput]),
      )
    } yield ()

    /** HTTP PUT /remove/customer-business-contacts */
    override def removeCustomerBusinessContactsPut(
        organizationID: UUID,
        removeCustomerBusinessContactsPutRequestSmithy: smithy.RemoveCustomerBusinessContactsPutRequest,
    ): ServiceTask[Unit] = for {
      removeCustomerBusinessContactsPutRequest <-
        customerBookRequestValidator.validatedRemoveCustomerBusinessContactsPutRequest(
          removeCustomerBusinessContactsPutRequestSmithy
        )
      _ <- customerBookRepository.removeCustomerBusinessContacts(
        OrganizationID(organizationID),
        removeCustomerBusinessContactsPutRequest.customerID,
        removeCustomerBusinessContactsPutRequest.customerBusinessContactIDs,
      )
    } yield ()

    /** HTTP GET /get/customer-individual/{customerID} */
    override def getCustomerIndividualGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.GetCustomerIndividualGetResponse] = for {
      customerIndividualDetailsRow <- customerBookRepository
        .getCustomerIndividual(OrganizationID(organizationID), CustomerID(customerID))
        .someOrFail(
          ServiceError.InternalServerError.UnexpectedError(
            s"Customer individual not found for customerID: [$customerID]"
          )
        )
    } yield smithy.GetCustomerIndividualGetResponse(
      customerID = customerIndividualDetailsRow.customerID.value,
      fullName = customerIndividualDetailsRow.fullName.value,
      emails = customerIndividualDetailsRow.emails.map(customerEmailEntryInput =>
        smithy.CustomerEmailEntryRequest(
          email = customerEmailEntryInput.email.value,
          isDefault = customerEmailEntryInput.isDefault,
        )
      ),
      phoneNumbers = customerIndividualDetailsRow.phoneNumbers.map(customerPhoneNumberEntryInput =>
        smithy.CustomerPhoneNumberEntryRequest(
          phoneNumber = smithy.PhoneNumberRequest(
            phoneNationalNumber = customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
            phoneCountryCode = customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
          ),
          isDefault = customerPhoneNumberEntryInput.isDefault,
        )
      ),
      addressLine1 = customerIndividualDetailsRow.addressLine1.map(_.value),
      addressLine2 = customerIndividualDetailsRow.addressLine2.map(_.value),
      city = customerIndividualDetailsRow.city.map(_.value),
      postalCode = customerIndividualDetailsRow.postalCode.map(_.value),
      country = customerIndividualDetailsRow.country.map(_.value),
    )

    /** HTTP GET /get/customer-business/{customerID} */
    override def getCustomerBusinessGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.GetCustomerBusinessGetResponse] = for {
      customerBusinessDetailsRow <- customerBookRepository
        .getCustomerBusiness(OrganizationID(organizationID), CustomerID(customerID))
        .someOrFail(
          ServiceError.InternalServerError.UnexpectedError(s"Customer business not found for customerID: [$customerID]")
        )
    } yield smithy.GetCustomerBusinessGetResponse(
      customerID = customerBusinessDetailsRow.customerID.value,
      businessName = customerBusinessDetailsRow.businessName.value,
      emails = customerBusinessDetailsRow.emails.map(customerEmailEntryInput =>
        smithy.CustomerEmailEntryRequest(
          email = customerEmailEntryInput.email.value,
          isDefault = customerEmailEntryInput.isDefault,
        )
      ),
      taxID = customerBusinessDetailsRow.taxID.map(_.value),
      phoneNumbers = customerBusinessDetailsRow.phoneNumbers.map(customerPhoneNumberEntryInput =>
        smithy.CustomerPhoneNumberEntryRequest(
          phoneNumber = smithy.PhoneNumberRequest(
            phoneNationalNumber = customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
            phoneCountryCode = customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
          ),
          isDefault = customerPhoneNumberEntryInput.isDefault,
        )
      ),
      addressLine1 = customerBusinessDetailsRow.addressLine1.map(_.value),
      addressLine2 = customerBusinessDetailsRow.addressLine2.map(_.value),
      city = customerBusinessDetailsRow.city.map(_.value),
      postalCode = customerBusinessDetailsRow.postalCode.map(_.value),
      country = customerBusinessDetailsRow.country.map(_.value),
    )

    /** HTTP GET /get/customers */
    override def getCustomersGet(
        organizationID: UUID
    ): ServiceTask[smithy.GetCustomersGetResponse] = for {
      customerSummaryRows <- customerBookRepository.getCustomers(OrganizationID(organizationID))
    } yield smithy.GetCustomersGetResponse(
      customers = customerSummaryRows.map(customerSummaryRow =>
        smithy.GetCustomer(
          customerID = customerSummaryRow.customerID.value,
          displayName = customerSummaryRow.displayName.value,
          customerType = customerTypeFromDomainToSmithy(customerSummaryRow.customerType),
        )
      )
    )

  }

  private def observed(
      service: smithy.CustomerBookService[ServiceTask]
  ): smithy.CustomerBookService[Task] =
    new smithy.CustomerBookService[Task] {

      /** HTTP POST /insert/customer-individual */
      override def insertCustomerIndividualPost(
          organizationID: UUID,
          insertCustomerIndividualPostRequestSmithy: smithy.InsertCustomerIndividualPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerIndividualPost(organizationID, insertCustomerIndividualPostRequestSmithy)
        )

      /** HTTP POST /insert/customer-individuals */
      override def insertCustomerIndividualsPost(
          organizationID: UUID,
          insertCustomerIndividualsPostRequestSmithy: smithy.InsertCustomerIndividualsPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerIndividualsPost(organizationID, insertCustomerIndividualsPostRequestSmithy)
        )

      /** HTTP POST /insert/customer-business */
      override def insertCustomerBusinessPost(
          organizationID: UUID,
          insertCustomerBusinessPostRequestSmithy: smithy.InsertCustomerBusinessPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerBusinessPost(organizationID, insertCustomerBusinessPostRequestSmithy)
        )

      /** HTTP POST /insert/customer-businesses */
      override def insertCustomerBusinessesPost(
          organizationID: UUID,
          insertCustomerBusinessesPostRequestSmithy: smithy.InsertCustomerBusinessesPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerBusinessesPost(organizationID, insertCustomerBusinessesPostRequestSmithy)
        )

      /** HTTP POST /insert/customers */
      override def insertCustomersPost(
          organizationID: UUID,
          insertCustomersPostRequestSmithy: smithy.InsertCustomersPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomersPost(organizationID, insertCustomersPostRequestSmithy)
        )

      /** HTTP PUT /update/customer-individual */
      override def updateCustomerIndividualPut(
          organizationID: UUID,
          updateCustomerIndividualPutRequestSmithy: smithy.UpdateCustomerIndividualPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.updateCustomerIndividualPut(organizationID, updateCustomerIndividualPutRequestSmithy)
        )

      /** HTTP PUT /update/customer-business */
      override def updateCustomerBusinessPut(
          organizationID: UUID,
          updateCustomerBusinessPutRequestSmithy: smithy.UpdateCustomerBusinessPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.updateCustomerBusinessPut(organizationID, updateCustomerBusinessPutRequestSmithy)
        )

      /** HTTP PUT /add/customer-business-contacts */
      override def addCustomerBusinessContactsPut(
          organizationID: UUID,
          addCustomerBusinessContactsPutRequestSmithy: smithy.AddCustomerBusinessContactsPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.addCustomerBusinessContactsPut(organizationID, addCustomerBusinessContactsPutRequestSmithy)
        )

      /** HTTP PUT /remove/customer-business-contacts */
      override def removeCustomerBusinessContactsPut(
          organizationID: UUID,
          removeCustomerBusinessContactsPutRequestSmithy: smithy.RemoveCustomerBusinessContactsPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.removeCustomerBusinessContactsPut(organizationID, removeCustomerBusinessContactsPutRequestSmithy)
        )

      /** HTTP GET /get/customer-individual/{customerID} */
      override def getCustomerIndividualGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.GetCustomerIndividualGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomerIndividualGet(organizationID, customerID))

      /** HTTP GET /get/customer-business/{customerID} */
      override def getCustomerBusinessGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.GetCustomerBusinessGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomerBusinessGet(organizationID, customerID))

      /** HTTP GET /get/customers */
      override def getCustomersGet(
          organizationID: UUID
      ): Task[smithy.GetCustomersGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomersGet(organizationID))
    }

  val local = ZLayer.derive[CustomerBookServiceImpl].project[smithy.CustomerBookService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
