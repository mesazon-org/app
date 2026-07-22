package io.mesazon.gateway.repository

import cats.data.NonEmptyList
import io.github.gaelrenoux.tranzactio.{DatabaseOps, DbException}
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CustomerBookQueries
import io.mesazon.generator.IDGenerator
import org.postgresql.util.{PSQLException, PSQLState}
import org.typelevel.doobie.Transactor
import zio.*

import java.time.Instant

trait CustomerBookRepository {

  def insertCustomerIndividual(
      organizationID: OrganizationID,
      insertCustomerIndividualInput: InsertCustomerIndividualInput,
  ): IO[ServiceError, CustomerID]

  def insertCustomerIndividuals(
      organizationID: OrganizationID,
      insertCustomerIndividualInputs: List[InsertCustomerIndividualInput],
  ): IO[ServiceError, List[CustomerID]]

  def updateCustomerIndividual(
      organizationID: OrganizationID,
      customerID: CustomerID,
      fullNameOptUpdate: Option[CustomerFullName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressLine1OptUpdate: Option[CustomerAddressLine1] = None,
      addressLine2OptUpdate: Option[CustomerAddressLine2] = None,
      cityOptUpdate: Option[CustomerCity] = None,
      postalCodeOptUpdate: Option[CustomerPostalCode] = None,
      countryOptUpdate: Option[CustomerCountry] = None,
  ): IO[ServiceError, CustomerIndividualDetailsRow]

  def insertCustomerBusiness(
      organizationID: OrganizationID,
      insertCustomerBusinessInput: InsertCustomerBusinessInput,
  ): IO[ServiceError, CustomerID]

  def insertCustomerBusinesses(
      organizationID: OrganizationID,
      insertCustomerBusinessInputs: List[InsertCustomerBusinessInput],
  ): IO[ServiceError, List[CustomerID]]

  def updateCustomerBusiness(
      organizationID: OrganizationID,
      customerID: CustomerID,
      businessNameOptUpdate: Option[CustomerBusinessName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      taxIDOptUpdate: Option[CustomerTaxID] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressLine1OptUpdate: Option[CustomerAddressLine1] = None,
      addressLine2OptUpdate: Option[CustomerAddressLine2] = None,
      cityOptUpdate: Option[CustomerCity] = None,
      postalCodeOptUpdate: Option[CustomerPostalCode] = None,
      countryOptUpdate: Option[CustomerCountry] = None,
  ): IO[ServiceError, CustomerBusinessDetailsRow]

  def insertCustomers(
      organizationID: OrganizationID,
      insertCustomerIndividualInputs: List[InsertCustomerIndividualInput],
      insertCustomerBusinessInputs: List[InsertCustomerBusinessInput],
  ): IO[ServiceError, List[CustomerID]]

  def addCustomerBusinessContacts(
      organizationID: OrganizationID,
      customerID: CustomerID,
      customerBusinessContactInputs: List[CustomerBusinessContactInput],
  ): IO[ServiceError, List[CustomerBusinessContactRow]]

  def removeCustomerBusinessContacts(
      organizationID: OrganizationID,
      customerID: CustomerID,
      customerBusinessContactIDs: List[CustomerBusinessContactID],
  ): IO[ServiceError, Unit]

  def getCustomerIndividual(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): IO[ServiceError, Option[CustomerIndividualDetailsRow]]

  def getCustomerBusiness(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): IO[ServiceError, Option[CustomerBusinessDetailsRow]]

  def getCustomers(
      organizationID: OrganizationID
  ): IO[ServiceError, List[CustomerSummaryRow]]
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

  case class InsertCustomerIndividualInput(
      fullName: CustomerFullName,
      emails: List[CustomerEmailEntryInput],
      phoneNumbers: List[CustomerPhoneNumberEntryInput],
      addressLine1: Option[CustomerAddressLine1],
      addressLine2: Option[CustomerAddressLine2],
      city: Option[CustomerCity],
      postalCode: Option[CustomerPostalCode],
      country: Option[CustomerCountry],
  )

  case class InsertCustomerBusinessInput(
      businessName: CustomerBusinessName,
      emails: List[CustomerEmailEntryInput],
      taxID: Option[CustomerTaxID],
      phoneNumbers: List[CustomerPhoneNumberEntryInput],
      addressLine1: Option[CustomerAddressLine1],
      addressLine2: Option[CustomerAddressLine2],
      city: Option[CustomerCity],
      postalCode: Option[CustomerPostalCode],
      country: Option[CustomerCountry],
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
      customerRow = buildCustomerRow(organizationID, customerID, CustomerType.Individual, instantNow)
      detailsRow  = buildCustomerIndividualDetailsRow(
        organizationID,
        customerID,
        insertCustomerIndividualInput,
        instantNow,
      )
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerRow(customerRow)
            _ <- customerBookQueries.insertCustomerIndividualDetailsRow(detailsRow)
          } yield ()
        )
        .mapError(toServiceError(s"Failed to insert customer individual with ID: [$customerID]"))
    } yield customerID

    override def insertCustomerIndividuals(
        organizationID: OrganizationID,
        insertCustomerIndividualInputs: List[InsertCustomerIndividualInput],
    ): IO[ServiceError, List[CustomerID]] = for {
      instantNow            <- timeProvider.instantNow
      customerIDsWithInputs <- ZIO.foreach(insertCustomerIndividualInputs)(input =>
        generateCustomerID.map(customerID => (customerID = customerID, input = input))
      )
      customerRows = customerIDsWithInputs.map(customerIDWithInput =>
        buildCustomerRow(organizationID, customerIDWithInput.customerID, CustomerType.Individual, instantNow)
      )
      detailsRows = customerIDsWithInputs.map(customerIDWithInput =>
        buildCustomerIndividualDetailsRow(
          organizationID,
          customerIDWithInput.customerID,
          customerIDWithInput.input,
          instantNow,
        )
      )
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerRows(customerRows)
            _ <- customerBookQueries.insertCustomerIndividualDetailsRows(detailsRows)
          } yield ()
        )
        .mapError(toServiceError(s"Failed to insert customer individuals for organization ID: [$organizationID]"))
    } yield customerIDsWithInputs.map(_.customerID)

    override def updateCustomerIndividual(
        organizationID: OrganizationID,
        customerID: CustomerID,
        fullNameOptUpdate: Option[CustomerFullName],
        emailsOptUpdate: Option[List[CustomerEmailEntryInput]],
        phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]],
        addressLine1OptUpdate: Option[CustomerAddressLine1],
        addressLine2OptUpdate: Option[CustomerAddressLine2],
        cityOptUpdate: Option[CustomerCity],
        postalCodeOptUpdate: Option[CustomerPostalCode],
        countryOptUpdate: Option[CustomerCountry],
    ): IO[ServiceError, CustomerIndividualDetailsRow] = for {
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
            addressLine1OptUpdate,
            addressLine2OptUpdate,
            cityOptUpdate,
            postalCodeOptUpdate,
            countryOptUpdate,
          )
        )
        .mapError(toServiceError(s"Failed to update customer individual with ID: [$customerID]"))
    } yield customerIndividualDetailsRowUpdated

    override def insertCustomerBusiness(
        organizationID: OrganizationID,
        insertCustomerBusinessInput: InsertCustomerBusinessInput,
    ): IO[ServiceError, CustomerID] = for {
      instantNow  <- timeProvider.instantNow
      customerID  <- generateCustomerID
      contactRows <- buildCustomerBusinessContactRows(
        organizationID,
        customerID,
        insertCustomerBusinessInput.customerBusinessContacts,
        instantNow,
      )
      customerRow = buildCustomerRow(organizationID, customerID, CustomerType.Business, instantNow)
      detailsRow  = buildCustomerBusinessDetailsRow(organizationID, customerID, insertCustomerBusinessInput, instantNow)
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerRow(customerRow)
            _ <- customerBookQueries.insertCustomerBusinessDetailsRow(detailsRow)
            _ <- customerBookQueries.insertCustomerBusinessContactRows(contactRows)
          } yield ()
        )
        .mapError(toServiceError(s"Failed to insert customer business with ID: [$customerID]"))
    } yield customerID

    override def insertCustomerBusinesses(
        organizationID: OrganizationID,
        insertCustomerBusinessInputs: List[InsertCustomerBusinessInput],
    ): IO[ServiceError, List[CustomerID]] = for {
      instantNow            <- timeProvider.instantNow
      customerIDsWithInputs <- ZIO.foreach(insertCustomerBusinessInputs)(input =>
        generateCustomerID.map(customerID => (customerID = customerID, input = input))
      )
      customerRows = customerIDsWithInputs.map(customerIDWithInput =>
        buildCustomerRow(organizationID, customerIDWithInput.customerID, CustomerType.Business, instantNow)
      )
      detailsRows = customerIDsWithInputs.map(customerIDWithInput =>
        buildCustomerBusinessDetailsRow(
          organizationID,
          customerIDWithInput.customerID,
          customerIDWithInput.input,
          instantNow,
        )
      )
      contactRows <- ZIO
        .foreach(customerIDsWithInputs)(customerIDWithInput =>
          buildCustomerBusinessContactRows(
            organizationID,
            customerIDWithInput.customerID,
            customerIDWithInput.input.customerBusinessContacts,
            instantNow,
          )
        )
        .map(_.flatten)
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerRows(customerRows)
            _ <- customerBookQueries.insertCustomerBusinessDetailsRows(detailsRows)
            _ <- customerBookQueries.insertCustomerBusinessContactRows(contactRows)
          } yield ()
        )
        .mapError(toServiceError(s"Failed to insert customer businesses for organization ID: [$organizationID]"))
    } yield customerIDsWithInputs.map(_.customerID)

    override def updateCustomerBusiness(
        organizationID: OrganizationID,
        customerID: CustomerID,
        businessNameOptUpdate: Option[CustomerBusinessName],
        emailsOptUpdate: Option[List[CustomerEmailEntryInput]],
        taxIDOptUpdate: Option[CustomerTaxID],
        phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]],
        addressLine1OptUpdate: Option[CustomerAddressLine1],
        addressLine2OptUpdate: Option[CustomerAddressLine2],
        cityOptUpdate: Option[CustomerCity],
        postalCodeOptUpdate: Option[CustomerPostalCode],
        countryOptUpdate: Option[CustomerCountry],
    ): IO[ServiceError, CustomerBusinessDetailsRow] = for {
      instantNow                        <- timeProvider.instantNow
      customerBusinessDetailsRowUpdated <- database
        .transactionOrWiden(
          customerBookQueries.updateCustomerBusinessDetailsRow(
            organizationID,
            customerID,
            UpdatedAt(instantNow),
            businessNameOptUpdate,
            emailsOptUpdate,
            taxIDOptUpdate,
            phoneNumbersOptUpdate,
            addressLine1OptUpdate,
            addressLine2OptUpdate,
            cityOptUpdate,
            postalCodeOptUpdate,
            countryOptUpdate,
          )
        )
        .mapError(toServiceError(s"Failed to update customer business with ID: [$customerID]"))
    } yield customerBusinessDetailsRowUpdated

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
      individualCustomerRows = customerIDsWithIndividualInputs.map(customerIDWithInput =>
        buildCustomerRow(organizationID, customerIDWithInput.customerID, CustomerType.Individual, instantNow)
      )
      businessCustomerRows = customerIDsWithBusinessInputs.map(customerIDWithInput =>
        buildCustomerRow(organizationID, customerIDWithInput.customerID, CustomerType.Business, instantNow)
      )
      individualDetailsRows = customerIDsWithIndividualInputs.map(customerIDWithInput =>
        buildCustomerIndividualDetailsRow(
          organizationID,
          customerIDWithInput.customerID,
          customerIDWithInput.input,
          instantNow,
        )
      )
      businessDetailsRows = customerIDsWithBusinessInputs.map(customerIDWithInput =>
        buildCustomerBusinessDetailsRow(
          organizationID,
          customerIDWithInput.customerID,
          customerIDWithInput.input,
          instantNow,
        )
      )
      contactRows <- ZIO
        .foreach(customerIDsWithBusinessInputs)(customerIDWithInput =>
          buildCustomerBusinessContactRows(
            organizationID,
            customerIDWithInput.customerID,
            customerIDWithInput.input.customerBusinessContacts,
            instantNow,
          )
        )
        .map(_.flatten)
      _ <- database
        .transactionOrWiden(
          for {
            _ <- customerBookQueries.insertCustomerRows(individualCustomerRows ++ businessCustomerRows)
            _ <- customerBookQueries.insertCustomerIndividualDetailsRows(individualDetailsRows)
            _ <- customerBookQueries.insertCustomerBusinessDetailsRows(businessDetailsRows)
            _ <- customerBookQueries.insertCustomerBusinessContactRows(contactRows)
          } yield ()
        )
        .mapError(toServiceError(s"Failed to insert customers for organization ID: [$organizationID]"))
    } yield customerIDsWithIndividualInputs.map(_.customerID) ++ customerIDsWithBusinessInputs.map(_.customerID)

    override def addCustomerBusinessContacts(
        organizationID: OrganizationID,
        customerID: CustomerID,
        customerBusinessContactInputs: List[CustomerBusinessContactInput],
    ): IO[ServiceError, List[CustomerBusinessContactRow]] = for {
      instantNow  <- timeProvider.instantNow
      contactRows <- buildCustomerBusinessContactRows(
        organizationID,
        customerID,
        customerBusinessContactInputs,
        instantNow,
      )
      _ <- database
        .transactionOrWiden(
          customerBookQueries.insertCustomerBusinessContactRows(contactRows)
        )
        .mapError(toServiceError(s"Failed to add customer business contacts for customer ID: [$customerID]"))
    } yield contactRows

    override def removeCustomerBusinessContacts(
        organizationID: OrganizationID,
        customerID: CustomerID,
        customerBusinessContactIDs: List[CustomerBusinessContactID],
    ): IO[ServiceError, Unit] =
      NonEmptyList.fromList(customerBusinessContactIDs) match {
        case None                                     => ZIO.unit
        case Some(customerBusinessContactIDsNonEmpty) =>
          database
            .transactionOrWiden(
              customerBookQueries.deleteCustomerBusinessContactRows(
                organizationID,
                customerID,
                customerBusinessContactIDsNonEmpty,
              )
            )
            .unit
            .mapError(toServiceError(s"Failed to remove customer business contacts for customer ID: [$customerID]"))
      }

    override def getCustomerIndividual(
        organizationID: OrganizationID,
        customerID: CustomerID,
    ): IO[ServiceError, Option[CustomerIndividualDetailsRow]] =
      database
        .transactionOrWiden(
          customerBookQueries.getCustomerIndividualDetailsRow(organizationID, customerID)
        )
        .mapError(toServiceError(s"Failed to get customer individual with ID: [$customerID]"))

    override def getCustomerBusiness(
        organizationID: OrganizationID,
        customerID: CustomerID,
    ): IO[ServiceError, Option[CustomerBusinessDetailsRow]] =
      database
        .transactionOrWiden(
          customerBookQueries.getCustomerBusinessDetailsRow(organizationID, customerID)
        )
        .mapError(toServiceError(s"Failed to get customer business with ID: [$customerID]"))

    override def getCustomers(
        organizationID: OrganizationID
    ): IO[ServiceError, List[CustomerSummaryRow]] =
      database
        .transactionOrWiden(
          customerBookQueries.getCustomerSummaryRows(organizationID)
        )
        .mapError(toServiceError(s"Failed to get customers for organization ID: [$organizationID]"))

    private def toServiceError(errorMessage: String)(dbException: DbException): ServiceError =
      findUniqueConstraintViolated(dbException) match {
        case Some(constraint) =>
          ServiceError.ConflictError.UniqueConstraintViolation(
            uniqueConstraintViolationMessage(constraint),
            dbException,
          )
        case None =>
          ServiceError.InternalServerError.RepositoryError(errorMessage, dbException)
      }

    private def findUniqueConstraintViolated(throwable: Throwable): Option[String] =
      throwable match {
        case null                                                                                             => None
        case psqlException: PSQLException if psqlException.getSQLState == PSQLState.UNIQUE_VIOLATION.getState =>
          Option(psqlException.getServerErrorMessage).flatMap(serverErrorMessage =>
            Option(serverErrorMessage.getConstraint)
          )
        case other => Option(other.getCause).filterNot(_ eq other).flatMap(findUniqueConstraintViolated)
      }

    private def uniqueConstraintViolationMessage(constraint: String): String =
      constraint match {
        case "uq_customer_individual_details_full_name" =>
          "A customer with the given full name already exists in this organization"
        case "uq_customer_business_details_business_name" =>
          "A customer with the given business name already exists in this organization"
        case "uq_customer_business_contact_email" =>
          "A business contact with the given email already exists for this customer"
        case "uq_customer_business_contact_phone_number" =>
          "A business contact with the given phone number already exists for this customer"
        case other =>
          s"A unique constraint was violated: [$other]"
      }

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

    private def buildCustomerRow(
        organizationID: OrganizationID,
        customerID: CustomerID,
        customerType: CustomerType,
        instantNow: Instant,
    ): CustomerRow =
      CustomerRow(
        organizationID,
        customerID,
        customerType,
        CustomerStatus.Active,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
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
        input.addressLine1,
        input.addressLine2,
        input.city,
        input.postalCode,
        input.country,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
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
        input.emails,
        input.phoneNumbers,
        input.taxID,
        input.addressLine1,
        input.addressLine2,
        input.city,
        input.postalCode,
        input.country,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
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
          )
        }
      }
  }

  val live = ZLayer.derive[CustomerBookRepositoryImpl].project[CustomerBookRepository](identity)
}
