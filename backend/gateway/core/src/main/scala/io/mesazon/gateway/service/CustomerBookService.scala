package io.mesazon.gateway.service

import io.github.iltotore.iron.chimney.given
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

    override def customerBookIndividualPost(
        organizationID: UUID,
        customerBookIndividualPostRequestSmithy: smithy.CustomerBookIndividualPostRequest,
    ): ServiceTask[smithy.CustomerBookIndividualPostResponse] = for {
      customerBookIndividualPostRequest <- customerBookRequestValidator.validatedCustomerBookIndividualPostRequest(
        customerBookIndividualPostRequestSmithy
      )
      customerID <- customerBookRepository.insertCustomerIndividual(
        OrganizationID(organizationID),
        customerBookIndividualPostRequest.transformInto[InsertCustomerIndividualInput],
      )
    } yield smithy.CustomerBookIndividualPostResponse(customerID.value)

    override def customerBookBusinessPost(
        organizationID: UUID,
        customerBookBusinessPostRequestSmithy: smithy.CustomerBookBusinessPostRequest,
    ): ServiceTask[smithy.CustomerBookBusinessPostResponse] = for {
      customerBookBusinessPostRequest <- customerBookRequestValidator.validatedCustomerBookBusinessPostRequest(
        customerBookBusinessPostRequestSmithy
      )
      customerID <- customerBookRepository.insertCustomerBusiness(
        OrganizationID(organizationID),
        customerBookBusinessPostRequest.transformInto[InsertCustomerBusinessInput],
      )
    } yield smithy.CustomerBookBusinessPostResponse(
      customerID.value
    )

    override def customerBookBatchPost(
        organizationID: UUID,
        customerBookBatchPostRequestSmithy: smithy.CustomerBookBatchPostRequest,
    ): ServiceTask[smithy.CustomerBookBatchPostResponse] = for {
      customerBookBatchPostRequest <- customerBookRequestValidator.validatedCustomerBookBatchPostRequest(
        customerBookBatchPostRequestSmithy
      )
      customerIDs <- customerBookRepository.insertCustomers(
        OrganizationID(organizationID),
        customerBookBatchPostRequest.customerIndividuals.transformInto[List[InsertCustomerIndividualInput]],
        customerBookBatchPostRequest.customerBusinesses.transformInto[List[InsertCustomerBusinessInput]],
      )
    } yield smithy.CustomerBookBatchPostResponse(
      customerIDs = customerIDs.transformInto[List[UUID]]
    )

    /** HTTP PUT /customer-book/individual/{customerID} */
    override def customerBookIndividualPut(
        organizationID: UUID,
        customerID: UUID,
        customerBookIndividualPutRequestSmithy: smithy.CustomerBookIndividualPutRequest,
    ): ServiceTask[Unit] = for {
      customerBookIndividualPutRequest <- customerBookRequestValidator.validatedCustomerBookIndividualPutRequest(
        customerBookIndividualPutRequestSmithy
      )
      _ <- customerBookRepository.updateCustomerIndividual(
        organizationID = OrganizationID(organizationID),
        customerID = CustomerID(customerID),
        fullNameOptUpdate = customerBookIndividualPutRequest.fullName,
        emailsOptUpdate = customerBookIndividualPutRequest.emails.transformInto[Option[List[CustomerEmailEntryInput]]],
        phoneNumbersOptUpdate =
          customerBookIndividualPutRequest.phoneNumbers.transformInto[Option[List[CustomerPhoneNumberEntryInput]]],
        addressesOptUpdate = customerBookIndividualPutRequest.addresses,
      )
    } yield ()

    override def customerBookBusinessPut(
        organizationID: UUID,
        customerID: UUID,
        customerBookBusinessPutRequestSmithy: smithy.CustomerBookBusinessPutRequest,
    ): ServiceTask[Unit] = for {
      customerBookBusinessPutRequest <- customerBookRequestValidator.validatedCustomerBookBusinessPutRequest(
        customerBookBusinessPutRequestSmithy
      )
      _ <- customerBookRepository.updateCustomerBusiness(
        OrganizationID(organizationID),
        CustomerID(customerID),
        businessNameOptUpdate = customerBookBusinessPutRequest.businessName,
        emailsOptUpdate = customerBookBusinessPutRequest.emails.transformInto[Option[List[CustomerEmailEntryInput]]],
        taxIDOptUpdate = customerBookBusinessPutRequest.taxID,
        phoneNumbersOptUpdate =
          customerBookBusinessPutRequest.phoneNumbers.transformInto[Option[List[CustomerPhoneNumberEntryInput]]],
        addressesOptUpdate = customerBookBusinessPutRequest.addresses,
        updateCustomerBusinessContactInputs = customerBookBusinessPutRequest.customerBusinessContactsUpdate
          .transformInto[List[UpdateCustomerBusinessContactInput]],
        insertCustomerBusinessContactInputs = customerBookBusinessPutRequest.customerBusinessContactsInsert
          .transformInto[List[CustomerBusinessContactInput]],
        archiveCustomerBusinessContactIDs =
          customerBookBusinessPutRequest.customerBusinessContactsArchive.map(_.customerBusinessContactID),
      )
    } yield ()

    override def customerBookArchivePut(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[Unit] =
      customerBookRepository.archiveCustomer(OrganizationID(organizationID), CustomerID(customerID)).unit

    override def customerBookIndividualGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.CustomerBookIndividualGetResponse] = for {
      customerIndividualDetailsRow <- customerBookRepository
        .getCustomerIndividual(OrganizationID(organizationID), CustomerID(customerID))
        .someOrFail(
          ServiceError.InternalServerError.UnexpectedError(
            s"Customer individual not found for customerID: [$customerID]"
          )
        )
    } yield customerIndividualDetailsRow.transformInto[smithy.CustomerBookIndividualGetResponse]

    override def customerBookBusinessGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.CustomerBookBusinessGetResponse] = for {
      customerBusinessWithContactsRow <- customerBookRepository
        .getCustomerBusiness(OrganizationID(organizationID), CustomerID(customerID))
        .someOrFail(
          ServiceError.InternalServerError.UnexpectedError(s"Customer business not found for customerID: [$customerID]")
        )
    } yield customerBusinessWithContactsRow.customerBusinessDetailsRow
      .into[smithy.CustomerBookBusinessGetResponse]
      .withFieldConst(
        _.customerBusinessContacts,
        customerBusinessWithContactsRow.customerBusinessContactRows.transformInto[List[smithy.CustomerBusinessContact]],
      )
      .transform

    override def customerBookSummaryGet(
        organizationID: UUID,
        customerIDs: List[UUID],
    ): ServiceTask[smithy.CustomerBookSummaryGetResponse] = for {
      customerSummaryRows <- customerBookRepository.getCustomerSummaries(
        OrganizationID(organizationID),
        customerIDs.map(CustomerID.apply),
      )
    } yield smithy.CustomerBookSummaryGetResponse(
      customers = customerSummaryRows.transformInto[List[smithy.CustomerSummary]]
    )

    override def customerBookUniquenessGet(
        organizationID: UUID,
        name: Option[String],
        customerType: Option[smithy.CustomerType],
        customerIDOpt: Option[UUID],
    ): ServiceTask[smithy.CustomerBookUniquenessGetResponse] = for {
      customerBookUniquenessGetRequest <- customerBookRequestValidator.validatedCustomerBookUniquenessGetRequest(
        smithy.CustomerBookUniquenessGetInput(organizationID, name, customerType, customerIDOpt)
      )
      isNameUnique <- customerBookRepository.isUniqueCustomerName(
        OrganizationID(organizationID),
        customerBookUniquenessGetRequest.name,
        customerBookUniquenessGetRequest.customerType,
        customerBookUniquenessGetRequest.customerID,
      )
    } yield smithy.CustomerBookUniquenessGetResponse(isNameUnique = isNameUnique)

  }

  private def observed(
      service: smithy.CustomerBookService[ServiceTask]
  ): smithy.CustomerBookService[Task] =
    new smithy.CustomerBookService[Task] {

      override def customerBookIndividualPost(
          organizationID: UUID,
          customerBookIndividualPostRequestSmithy: smithy.CustomerBookIndividualPostRequest,
      ): Task[smithy.CustomerBookIndividualPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.customerBookIndividualPost(organizationID, customerBookIndividualPostRequestSmithy)
        )

      override def customerBookBusinessPost(
          organizationID: UUID,
          customerBookBusinessPostRequestSmithy: smithy.CustomerBookBusinessPostRequest,
      ): Task[smithy.CustomerBookBusinessPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.customerBookBusinessPost(organizationID, customerBookBusinessPostRequestSmithy)
        )

      override def customerBookBatchPost(
          organizationID: UUID,
          customerBookBatchPostRequestSmithy: smithy.CustomerBookBatchPostRequest,
      ): Task[smithy.CustomerBookBatchPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.customerBookBatchPost(organizationID, customerBookBatchPostRequestSmithy)
        )

      /** HTTP PUT /customer-book/individual/{customerID} */
      override def customerBookIndividualPut(
          organizationID: UUID,
          customerID: UUID,
          customerBookIndividualPutRequestSmithy: smithy.CustomerBookIndividualPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.customerBookIndividualPut(organizationID, customerID, customerBookIndividualPutRequestSmithy)
        )

      override def customerBookBusinessPut(
          organizationID: UUID,
          customerID: UUID,
          customerBookBusinessPutRequestSmithy: smithy.CustomerBookBusinessPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.customerBookBusinessPut(organizationID, customerID, customerBookBusinessPutRequestSmithy)
        )

      override def customerBookArchivePut(
          organizationID: UUID,
          customerID: UUID,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.customerBookArchivePut(organizationID, customerID))

      override def customerBookIndividualGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.CustomerBookIndividualGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.customerBookIndividualGet(organizationID, customerID))

      override def customerBookBusinessGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.CustomerBookBusinessGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.customerBookBusinessGet(organizationID, customerID))

      override def customerBookSummaryGet(
          organizationID: UUID,
          customerIDs: List[UUID],
      ): Task[smithy.CustomerBookSummaryGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.customerBookSummaryGet(organizationID, customerIDs))

      override def customerBookUniquenessGet(
          organizationID: UUID,
          name: Option[String],
          customerType: Option[smithy.CustomerType],
          customerIDOpt: Option[UUID],
      ): Task[smithy.CustomerBookUniquenessGetResponse] =
        HttpErrorHandler.errorResponseHandler(
          service.customerBookUniquenessGet(organizationID, name, customerType, customerIDOpt)
        )

    }

  val local = ZLayer.derive[CustomerBookServiceImpl].project[smithy.CustomerBookService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
