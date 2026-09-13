package io.mesazon.gateway.service

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CustomerBookRepository
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.validation.service.CustomerBookRequestValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import zio.*

import java.util.UUID

object CustomerBookService {

  // iron-chimney only unwraps a refined newtype into its own base type; CustomerPhoneNumber's base type is the
  // PhoneNumber case class, not smithy.PhoneNumberRequest, so Chimney's nested-case-class derivation needs this
  // explicit hop (unwrap, then derive PhoneNumber -> PhoneNumberRequest) to map every phoneNumber field in this file.
  private given Transformer[CustomerPhoneNumber, smithy.PhoneNumberRequest] =
    customerPhoneNumber => (customerPhoneNumber.value: PhoneNumber).transformInto[smithy.PhoneNumberRequest]

  private final class CustomerBookServiceImpl(
      customerBookRequestValidator: CustomerBookRequestValidator,
      customerBookRepository: CustomerBookRepository,
  ) extends smithy.CustomerBookService[ServiceTask] {

    /** HTTP POST /insert/customer-individual */
    override def insertCustomerIndividualPost(
        organizationID: UUID,
        insertCustomerIndividualPostRequestSmithy: smithy.InsertCustomerIndividualPostRequest,
    ): ServiceTask[smithy.InsertCustomerIndividualPostResponse] = for {
      insertCustomerIndividualPostRequest <- customerBookRequestValidator.validatedInsertCustomerIndividualPostRequest(
        insertCustomerIndividualPostRequestSmithy
      )
      customerIndividualDetailsRow <- customerBookRepository.insertCustomerIndividual(
        OrganizationID(organizationID),
        insertCustomerIndividualPostRequest.transformInto[InsertCustomerIndividualInput],
      )
    } yield customerIndividualDetailsRow.transformInto[smithy.InsertCustomerIndividualPostResponse]

    /** HTTP POST /insert/customer-individuals */
    override def insertCustomerIndividualsPost(
        organizationID: UUID,
        insertCustomerIndividualsPostRequestSmithy: smithy.InsertCustomerIndividualsPostRequest,
    ): ServiceTask[smithy.InsertCustomerIndividualsPostResponse] = for {
      insertCustomerIndividualsPostRequest <-
        customerBookRequestValidator.validatedInsertCustomerIndividualsPostRequest(
          insertCustomerIndividualsPostRequestSmithy
        )
      customerIndividualDetailsRows <- customerBookRepository.insertCustomerIndividuals(
        OrganizationID(organizationID),
        insertCustomerIndividualsPostRequest.customerIndividuals.map(_.transformInto[InsertCustomerIndividualInput]),
      )
    } yield smithy.InsertCustomerIndividualsPostResponse(
      customerIndividuals = customerIndividualDetailsRows.map(
        _.into[smithy.GetCustomer]
          .withFieldRenamed(_.fullName, _.name)
          .withFieldConst(_.customerType, customerTypeFromDomainToSmithy(CustomerType.Individual))
          .transform
      )
    )

    /** HTTP POST /insert/customer-business */
    override def insertCustomerBusinessPost(
        organizationID: UUID,
        insertCustomerBusinessPostRequestSmithy: smithy.InsertCustomerBusinessPostRequest,
    ): ServiceTask[smithy.InsertCustomerBusinessPostResponse] = for {
      insertCustomerBusinessPostRequest <- customerBookRequestValidator.validatedInsertCustomerBusinessPostRequest(
        insertCustomerBusinessPostRequestSmithy
      )
      customerBusinessInsertRow <- customerBookRepository.insertCustomerBusiness(
        OrganizationID(organizationID),
        insertCustomerBusinessPostRequest.transformInto[InsertCustomerBusinessInput],
      )
    } yield customerBusinessInsertRow.customerBusinessDetailsRow
      .into[smithy.InsertCustomerBusinessPostResponse]
      .withFieldConst(
        _.customerBusinessContacts,
        customerBusinessInsertRow.customerBusinessContactRows.map(
          _.transformInto[smithy.InsertCustomerBusinessContactResponse]
        ),
      )
      .transform

    /** HTTP POST /insert/customer-businesses */
    override def insertCustomerBusinessesPost(
        organizationID: UUID,
        insertCustomerBusinessesPostRequestSmithy: smithy.InsertCustomerBusinessesPostRequest,
    ): ServiceTask[smithy.InsertCustomerBusinessesPostResponse] = for {
      insertCustomerBusinessesPostRequest <- customerBookRequestValidator.validatedInsertCustomerBusinessesPostRequest(
        insertCustomerBusinessesPostRequestSmithy
      )
      customerBusinessInsertRows <- customerBookRepository.insertCustomerBusinesses(
        OrganizationID(organizationID),
        insertCustomerBusinessesPostRequest.customerBusinesses.map(_.transformInto[InsertCustomerBusinessInput]),
      )
    } yield smithy.InsertCustomerBusinessesPostResponse(
      customerBusinesses = customerBusinessInsertRows.map(
        _.customerBusinessDetailsRow
          .into[smithy.GetCustomer]
          .withFieldRenamed(_.businessName, _.name)
          .withFieldConst(_.customerType, customerTypeFromDomainToSmithy(CustomerType.Business))
          .transform
      )
    )

    /** HTTP POST /insert/customers */
    override def insertCustomersPost(
        organizationID: UUID,
        insertCustomersPostRequestSmithy: smithy.InsertCustomersPostRequest,
    ): ServiceTask[smithy.InsertCustomersPostResponse] = for {
      insertCustomersPostRequest <- customerBookRequestValidator.validatedInsertCustomersPostRequest(
        insertCustomersPostRequestSmithy
      )
      insertCustomersResult <- customerBookRepository.insertCustomers(
        OrganizationID(organizationID),
        insertCustomersPostRequest.customerIndividuals.map(_.transformInto[InsertCustomerIndividualInput]),
        insertCustomersPostRequest.customerBusinesses.map(_.transformInto[InsertCustomerBusinessInput]),
      )
    } yield smithy.InsertCustomersPostResponse(
      customers = insertCustomersResult.customerIndividualDetailsRows.map(
        _.into[smithy.GetCustomer]
          .withFieldRenamed(_.fullName, _.name)
          .withFieldConst(_.customerType, customerTypeFromDomainToSmithy(CustomerType.Individual))
          .transform
      ) ++
        insertCustomersResult.customerBusinessInsertRows.map(
          _.customerBusinessDetailsRow
            .into[smithy.GetCustomer]
            .withFieldRenamed(_.businessName, _.name)
            .withFieldConst(_.customerType, customerTypeFromDomainToSmithy(CustomerType.Business))
            .transform
        )
    )

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
    ): ServiceTask[Unit] =
      customerBookRepository.removeCustomerBusinessContacts(
        OrganizationID(organizationID),
        CustomerID(removeCustomerBusinessContactsPutRequestSmithy.customerID),
        removeCustomerBusinessContactsPutRequestSmithy.customerBusinessContacts.map(customerBusinessContact =>
          CustomerBusinessContactID(customerBusinessContact.customerBusinessContactID)
        ),
      )

    /** HTTP PUT /archive/customer */
    override def archiveCustomerPut(
        organizationID: UUID,
        archiveCustomerPutRequestSmithy: smithy.ArchiveCustomerPutRequest,
    ): ServiceTask[Unit] =
      // Archiving a missing (or already-archived) customer is a silent no-op — no 404/500.
      customerBookRepository
        .archiveCustomer(
          OrganizationID(organizationID),
          CustomerID(archiveCustomerPutRequestSmithy.customerID),
        )
        .unit

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
    } yield customerIndividualDetailsRow.transformInto[smithy.GetCustomerIndividualGetResponse]

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
    } yield customerBusinessDetailsRow.transformInto[smithy.GetCustomerBusinessGetResponse]

    /** HTTP GET /get/customers */
    override def getCustomersGet(
        organizationID: UUID
    ): ServiceTask[smithy.GetCustomersGetResponse] = for {
      customerSummaryRows <- customerBookRepository.getCustomers(OrganizationID(organizationID))
    } yield smithy.GetCustomersGetResponse(
      customers = customerSummaryRows.map(
        _.into[smithy.GetCustomer]
          .withFieldComputed(
            _.customerType,
            customerSummaryRow => customerTypeFromDomainToSmithy(customerSummaryRow.customerType),
          )
          .transform
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
      ): Task[smithy.InsertCustomerIndividualPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerIndividualPost(organizationID, insertCustomerIndividualPostRequestSmithy)
        )

      /** HTTP POST /insert/customer-individuals */
      override def insertCustomerIndividualsPost(
          organizationID: UUID,
          insertCustomerIndividualsPostRequestSmithy: smithy.InsertCustomerIndividualsPostRequest,
      ): Task[smithy.InsertCustomerIndividualsPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerIndividualsPost(organizationID, insertCustomerIndividualsPostRequestSmithy)
        )

      /** HTTP POST /insert/customer-business */
      override def insertCustomerBusinessPost(
          organizationID: UUID,
          insertCustomerBusinessPostRequestSmithy: smithy.InsertCustomerBusinessPostRequest,
      ): Task[smithy.InsertCustomerBusinessPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerBusinessPost(organizationID, insertCustomerBusinessPostRequestSmithy)
        )

      /** HTTP POST /insert/customer-businesses */
      override def insertCustomerBusinessesPost(
          organizationID: UUID,
          insertCustomerBusinessesPostRequestSmithy: smithy.InsertCustomerBusinessesPostRequest,
      ): Task[smithy.InsertCustomerBusinessesPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCustomerBusinessesPost(organizationID, insertCustomerBusinessesPostRequestSmithy)
        )

      /** HTTP POST /insert/customers */
      override def insertCustomersPost(
          organizationID: UUID,
          insertCustomersPostRequestSmithy: smithy.InsertCustomersPostRequest,
      ): Task[smithy.InsertCustomersPostResponse] =
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

      /** HTTP PUT /archive/customer */
      override def archiveCustomerPut(
          organizationID: UUID,
          archiveCustomerPutRequestSmithy: smithy.ArchiveCustomerPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.archiveCustomerPut(organizationID, archiveCustomerPutRequestSmithy)
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
