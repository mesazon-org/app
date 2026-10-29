package io.mesazon.gateway.repository

import cats.data.NonEmptyList
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CustomerBookQueries
import io.mesazon.generator.IDGenerator
import org.typelevel.doobie.Transactor
import zio.*

import java.time.Instant

trait CustomerBookRepository {

  def insertCustomerIndividual(
      organizationID: OrganizationID,
      insertCustomerIndividualInput: InsertCustomerIndividualInput,
  ): IO[ServiceError, CustomerID]

  def updateCustomerIndividual(
      organizationID: OrganizationID,
      customerID: CustomerID,
      fullNameOptUpdate: Option[CustomerFullName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressesOptUpdate: Option[List[CustomerAddressEntry]] = None,
  ): IO[ServiceError, Option[CustomerIndividualDetailsRow]]

  def insertCustomerBusiness(
      organizationID: OrganizationID,
      insertCustomerBusinessInput: InsertCustomerBusinessInput,
  ): IO[ServiceError, CustomerID]

  def updateCustomerBusiness(
      organizationID: OrganizationID,
      customerID: CustomerID,
      businessNameOptUpdate: Option[CustomerBusinessName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      taxIDOptUpdate: Option[Option[CustomerTaxID]] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressesOptUpdate: Option[List[CustomerAddressEntry]] = None,
      updateCustomerBusinessContactInputs: List[UpdateCustomerBusinessContactInput] = Nil,
      insertCustomerBusinessContactInputs: List[CustomerBusinessContactInput] = Nil,
      archiveCustomerBusinessContactIDs: List[CustomerBusinessContactID] = Nil,
  ): IO[ServiceError, Option[CustomerBusinessDetailsRow]]

  def insertCustomers(
      organizationID: OrganizationID,
      insertCustomerIndividualInputs: List[InsertCustomerIndividualInput],
      insertCustomerBusinessInputs: List[InsertCustomerBusinessInput],
  ): IO[ServiceError, List[CustomerID]]

  def archiveCustomer(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): IO[ServiceError, Option[CustomerID]]

  def getCustomerIndividual(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): IO[ServiceError, Option[CustomerIndividualDetailsRow]]

  def getCustomerBusiness(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): IO[ServiceError, Option[CustomerBusinessWithContactsRow]]

  def getCustomerSummaries(
      organizationID: OrganizationID,
      customerIDs: List[CustomerID],
  ): IO[ServiceError, List[CustomerSummaryRow]]

  def isUniqueCustomerName(
      organizationID: OrganizationID,
      name: CustomerName,
      customerType: CustomerType,
      customerIDOpt: Option[CustomerID],
  ): IO[ServiceError, Boolean]
}

object CustomerBookRepository {

  case class CustomerEmailEntryInput(
      email: CustomerEmail,
      isDefault: Boolean,
  )

  case class CustomerPhoneNumberEntryInput(
      phoneNumber: CustomerPhoneNumber,
      isDefault: Boolean,
  )

  case class CustomerBusinessContactInput(
      fullName: CustomerFullName,
      role: Option[CustomerBusinessContactRole],
      email: Option[CustomerEmail],
      phoneNumber: Option[CustomerPhoneNumber],
  )

  case class UpdateCustomerBusinessContactInput(
      customerBusinessContactID: CustomerBusinessContactID,
      fullName: Option[CustomerFullName],
      role: Option[Option[CustomerBusinessContactRole]],
      email: Option[Option[CustomerEmail]],
      phoneNumber: Option[Option[CustomerPhoneNumber]],
  )

  case class InsertCustomerIndividualInput(
      fullName: CustomerFullName,
      emails: List[CustomerEmailEntryInput],
      phoneNumbers: List[CustomerPhoneNumberEntryInput],
      addresses: List[CustomerAddressEntry],
  )

  case class InsertCustomerBusinessInput(
      businessName: CustomerBusinessName,
      emails: List[CustomerEmailEntryInput],
      taxID: Option[CustomerTaxID],
      phoneNumbers: List[CustomerPhoneNumberEntryInput],
      addresses: List[CustomerAddressEntry],
      customerBusinessContacts: List[CustomerBusinessContactInput],
  )

  private final class CustomerBookRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      customerBookQueries: CustomerBookQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends CustomerBookRepository {

    override def insertCustomerIndividual(
        organizationID: OrganizationID,
        insertCustomerIndividualInput: InsertCustomerIndividualInput,
    ): IO[ServiceError, CustomerID] = for {
      instantNow <- timeProvider.instantNow
      customerID <- generateCustomerID
      customerIndividualDetailsRow = buildCustomerIndividualDetailsRow(
        organizationID,
        customerID,
        insertCustomerIndividualInput,
        instantNow,
      )
      _ <- database
        .transactionOrWiden(
          customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
        )
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to insert customer individual with ID: [$customerID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield customerID

    override def updateCustomerIndividual(
        organizationID: OrganizationID,
        customerID: CustomerID,
        fullNameOptUpdate: Option[CustomerFullName],
        emailsOptUpdate: Option[List[CustomerEmailEntryInput]],
        phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]],
        addressesOptUpdate: Option[List[CustomerAddressEntry]],
    ): IO[ServiceError, Option[CustomerIndividualDetailsRow]] = for {
      instantNow                          <- timeProvider.instantNow
      customerIndividualDetailsRowUpdated <- database
        .transactionOrWiden(
          customerBookQueries.updateCustomerIndividualDetailsRow(
            organizationID,
            customerID,
            UpdatedAt(instantNow),
            fullNameOptUpdate,
            emailsOptUpdate,
            phoneNumbersOptUpdate,
            addressesOptUpdate,
          )
        )
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to update customer individual with ID: [$customerID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield customerIndividualDetailsRowUpdated

    override def insertCustomerBusiness(
        organizationID: OrganizationID,
        insertCustomerBusinessInput: InsertCustomerBusinessInput,
    ): IO[ServiceError, CustomerID] = for {
      instantNow                  <- timeProvider.instantNow
      customerID                  <- generateCustomerID
      customerBusinessContactRows <- buildCustomerBusinessContactRows(
        organizationID,
        customerID,
        insertCustomerBusinessInput.customerBusinessContacts,
        instantNow,
      )
      customerBusinessDetailsRow = buildCustomerBusinessDetailsRow(
        organizationID,
        customerID,
        insertCustomerBusinessInput,
        instantNow,
      )
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            _ <- customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows)
          } yield ()
        )
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to insert customer business with ID: [$customerID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield customerID

    override def updateCustomerBusiness(
        organizationID: OrganizationID,
        customerID: CustomerID,
        businessNameOptUpdate: Option[CustomerBusinessName],
        emailsOptUpdate: Option[List[CustomerEmailEntryInput]],
        taxIDOptUpdate: Option[Option[CustomerTaxID]],
        phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]],
        addressesOptUpdate: Option[List[CustomerAddressEntry]],
        updateCustomerBusinessContactInputs: List[UpdateCustomerBusinessContactInput],
        insertCustomerBusinessContactInputs: List[CustomerBusinessContactInput],
        archiveCustomerBusinessContactIDs: List[CustomerBusinessContactID],
    ): IO[ServiceError, Option[CustomerBusinessDetailsRow]] = for {
      instantNow <- timeProvider.instantNow
      updatedAt = UpdatedAt(instantNow)
      customerBusinessContactRowsInsert <- buildCustomerBusinessContactRows(
        organizationID,
        customerID,
        insertCustomerBusinessContactInputs,
        instantNow,
      )
      customerBusinessDetailsRowUpdatedOpt <- database.transactionOrWiden {
        for {
          customerBusinessDetailsRowUpdatedOpt <- customerBookQueries.updateCustomerBusinessDetailsRow(
            organizationID,
            customerID,
            updatedAt,
            businessNameOptUpdate,
            emailsOptUpdate,
            taxIDOptUpdate,
            phoneNumbersOptUpdate,
            addressesOptUpdate,
          )
          _ <- customerBusinessDetailsRowUpdatedOpt match {
            case None    => ZIO.unit
            case Some(_) =>
              for {
                updateCustomerBusinessContactInputsOptNonEmpty = NonEmptyList.fromList(
                  updateCustomerBusinessContactInputs
                )
                customerBusinessContactRowsOpt <- ZIO.foreach(updateCustomerBusinessContactInputsOptNonEmpty)(
                  updateCustomerBusinessContactInputsNonEmpty =>
                    customerBookQueries.getCustomerBusinessContactRowsByIDs(
                      organizationID,
                      customerID,
                      updateCustomerBusinessContactInputsNonEmpty.map(_.customerBusinessContactID),
                    )
                )
                _ <- customerBusinessContactRowsOpt match {
                  case None                              => ZIO.unit
                  case Some(customerBusinessContactRows) =>
                    if (customerBusinessContactRows.size != updateCustomerBusinessContactInputs.size)
                      ZIO.fail(
                        ServiceError.InternalServerError.UnexpectedError(
                          s"Failed to update customer business with ID: [$customerID]. Some customer business contact IDs do not exist, belong to another customer business, or are archived. Requested: [${updateCustomerBusinessContactInputs.map(_.customerBusinessContactID)}], Found: [${customerBusinessContactRows}]"
                        )
                      )
                    else ZIO.unit
                }
                customerBusinessContactRowsOptUpdated = mergeUpdateCustomerBusinessContactInputs(
                  updateCustomerBusinessContactInputsOptNonEmpty,
                  customerBusinessContactRowsOpt.getOrElse(Nil),
                  updatedAt,
                )
                _ <- ZIO.foreach(customerBusinessContactRowsOptUpdated)(customerBusinessContactRowsUpdated =>
                  customerBookQueries.updateCustomerBusinessContactRows(
                    organizationID,
                    customerID,
                    customerBusinessContactRowsUpdated,
                  )
                )
                _ <- customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRowsInsert)
                _ <- ZIO.foreach(NonEmptyList.fromList(archiveCustomerBusinessContactIDs))(customerBusinessContactIDs =>
                  customerBookQueries.archiveCustomerBusinessContactRows(
                    organizationID,
                    customerID,
                    customerBusinessContactIDs,
                    updatedAt,
                  )
                )
              } yield ()
          }
        } yield customerBusinessDetailsRowUpdatedOpt
      }.mapError {
        case serviceError: ServiceError => serviceError
        case throwable                  =>
          catchUniqueConstraintViolation(
            s"Failed to update customer business with ID: [$customerID]",
            uniqueConstraintViolationMessage,
          )(throwable)
      }
    } yield customerBusinessDetailsRowUpdatedOpt

    override def insertCustomers(
        organizationID: OrganizationID,
        insertCustomerIndividualInputs: List[InsertCustomerIndividualInput],
        insertCustomerBusinessInputs: List[InsertCustomerBusinessInput],
    ): IO[ServiceError, List[CustomerID]] = for {
      instantNow                      <- timeProvider.instantNow
      customerIDsWithIndividualInputs <- ZIO.foreach(insertCustomerIndividualInputs)(input =>
        generateCustomerID.map(customerID => (customerID = customerID, input = input))
      )
      customerIDsWithBusinessInputs <- ZIO.foreach(insertCustomerBusinessInputs)(input =>
        generateCustomerID.map(customerID => (customerID = customerID, input = input))
      )
      customerIndividualDetailsRows = customerIDsWithIndividualInputs.map(customerIDWithInput =>
        buildCustomerIndividualDetailsRow(
          organizationID,
          customerIDWithInput.customerID,
          customerIDWithInput.input,
          instantNow,
        )
      )
      customerBusinessInsertRows <- ZIO.foreach(customerIDsWithBusinessInputs)(customerIDWithInput =>
        buildCustomerBusinessContactRows(
          organizationID,
          customerIDWithInput.customerID,
          customerIDWithInput.input.customerBusinessContacts,
          instantNow,
        ).map(customerBusinessContactRows =>
          (
            customerBusinessDetailsRow = buildCustomerBusinessDetailsRow(
              organizationID,
              customerIDWithInput.customerID,
              customerIDWithInput.input,
              instantNow,
            ),
            customerBusinessContactRows = customerBusinessContactRows,
          )
        )
      )
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows)
            _ <- customerBookQueries.insertCustomerBusinessDetailsRows(
              customerBusinessInsertRows.map(_.customerBusinessDetailsRow)
            )
            _ <- customerBookQueries.insertCustomerBusinessContactRows(
              customerBusinessInsertRows.flatMap(_.customerBusinessContactRows)
            )
          } yield ()
        )
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to insert customers for organization ID: [$organizationID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield customerIDsWithIndividualInputs.map(_.customerID) ++ customerIDsWithBusinessInputs.map(_.customerID)

    override def archiveCustomer(
        organizationID: OrganizationID,
        customerID: CustomerID,
    ): IO[ServiceError, Option[CustomerID]] = for {
      instantNow            <- timeProvider.instantNow
      customerIDOptArchived <- database
        .transactionOrWiden(
          customerBookQueries.archiveCustomerRow(organizationID, customerID, UpdatedAt(instantNow))
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to archive customer with ID: [$customerID]",
            e,
          )
        )
    } yield customerIDOptArchived

    override def getCustomerIndividual(
        organizationID: OrganizationID,
        customerID: CustomerID,
    ): IO[ServiceError, Option[CustomerIndividualDetailsRow]] =
      database
        .transactionOrWiden(
          customerBookQueries.getCustomerIndividualDetailsRow(organizationID, customerID)
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get customer individual with ID: [$customerID]",
            e,
          )
        )

    override def getCustomerBusiness(
        organizationID: OrganizationID,
        customerID: CustomerID,
    ): IO[ServiceError, Option[CustomerBusinessWithContactsRow]] =
      database
        .transactionOrWiden(customerBookQueries.getCustomerBusinessWithContactsRow(organizationID, customerID))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get customer business with ID: [$customerID]",
            e,
          )
        )

    override def getCustomerSummaries(
        organizationID: OrganizationID,
        customerIDs: List[CustomerID],
    ): IO[ServiceError, List[CustomerSummaryRow]] =
      database
        .transactionOrWiden(customerBookQueries.getCustomerSummaryRows(organizationID, customerIDs))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get customer summaries for organization ID: [$organizationID], customer IDs: [${customerIDs.mkString(", ")}]",
            e,
          )
        )

    override def isUniqueCustomerName(
        organizationID: OrganizationID,
        name: CustomerName,
        customerType: CustomerType,
        customerIDOpt: Option[CustomerID],
    ): IO[ServiceError, Boolean] =
      database
        .transactionOrWiden(customerBookQueries.isUniqueCustomerName(organizationID, name, customerType, customerIDOpt))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to check name uniqueness for organizationID: [$organizationID], name: [$name], customerType: [$customerType], customerIDOpt: [$customerIDOpt]",
            e,
          )
        )

    private val uniqueConstraintViolationMessage: PartialFunction[String, String] = { case "uq_customer_name" =>
      "A customer with the given name already exists in this organization"
    }

    private def mergeUpdateCustomerBusinessContactInputs(
        updateCustomerBusinessContactInputs: Option[NonEmptyList[UpdateCustomerBusinessContactInput]],
        customerBusinessContactRowsExisting: List[CustomerBusinessContactRow],
        updatedAt: UpdatedAt,
    ): Option[NonEmptyList[CustomerBusinessContactRow]] =
      updateCustomerBusinessContactInputs.flatMap(updateCustomerBusinessContactInputsNonEmpty =>
        updateCustomerBusinessContactInputsNonEmpty.traverse { updateCustomerBusinessContactInput =>
          customerBusinessContactRowsExisting
            .find(
              _.customerBusinessContactID == updateCustomerBusinessContactInput.customerBusinessContactID
            )
            .map { customerBusinessContactRowExisting =>
              customerBusinessContactRowExisting.copy(
                fullName =
                  updateCustomerBusinessContactInput.fullName.getOrElse(customerBusinessContactRowExisting.fullName),
                role = updateCustomerBusinessContactInput.role.getOrElse(customerBusinessContactRowExisting.role),
                email = updateCustomerBusinessContactInput.email.getOrElse(customerBusinessContactRowExisting.email),
                phoneNumber = updateCustomerBusinessContactInput.phoneNumber
                  .getOrElse(customerBusinessContactRowExisting.phoneNumber),
                updatedAt = updatedAt,
              )
            }
        }
      )

    private def generateCustomerID: IO[ServiceError, CustomerID] =
      idGenerator.generateID
        .map(CustomerID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct customerID: [$e]"))
        )

    private def generateCustomerBusinessContactID: IO[ServiceError, CustomerBusinessContactID] =
      idGenerator.generateID
        .map(CustomerBusinessContactID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError(s"Failed to construct customerBusinessContactID: [$e]")
            )
        )

    private def buildCustomerIndividualDetailsRow(
        organizationID: OrganizationID,
        customerID: CustomerID,
        input: InsertCustomerIndividualInput,
        instantNow: Instant,
    ): CustomerIndividualDetailsRow =
      CustomerIndividualDetailsRow(
        organizationID,
        customerID,
        input.fullName,
        input.emails,
        input.phoneNumbers,
        input.addresses,
        isArchived = false,
        createdAt = CreatedAt(instantNow),
        updatedAt = UpdatedAt(instantNow),
      )

    private def buildCustomerBusinessDetailsRow(
        organizationID: OrganizationID,
        customerID: CustomerID,
        input: InsertCustomerBusinessInput,
        instantNow: Instant,
    ): CustomerBusinessDetailsRow =
      CustomerBusinessDetailsRow(
        organizationID,
        customerID,
        input.businessName,
        input.taxID,
        input.emails,
        input.phoneNumbers,
        input.addresses,
        isArchived = false,
        createdAt = CreatedAt(instantNow),
        updatedAt = UpdatedAt(instantNow),
      )

    private def buildCustomerBusinessContactRows(
        organizationID: OrganizationID,
        customerID: CustomerID,
        contacts: List[CustomerBusinessContactInput],
        instantNow: Instant,
    ): IO[ServiceError, List[CustomerBusinessContactRow]] =
      ZIO.foreach(contacts) { contact =>
        generateCustomerBusinessContactID.map { customerBusinessContactID =>
          CustomerBusinessContactRow(
            organizationID,
            customerID,
            customerBusinessContactID,
            contact.fullName,
            contact.role,
            contact.email,
            contact.phoneNumber,
            CreatedAt(instantNow),
            UpdatedAt(instantNow),
            isArchived = false,
          )
        }
      }
  }

  val live = ZLayer.derive[CustomerBookRepositoryImpl].project[CustomerBookRepository](identity)
}
