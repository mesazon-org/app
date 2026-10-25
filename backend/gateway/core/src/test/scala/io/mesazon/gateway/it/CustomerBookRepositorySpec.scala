package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.CustomerBookRepository
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CustomerBookQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class CustomerBookRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  private inline val customerAddressEntriesCount = 3

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.customerTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.customerTable).zioValue
    }
  }

  "CustomerBookRepository" when {
    "insertCustomerIndividual" should {
      "insert a customer of type Individual and its details row in one transaction" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val customerID                    = arbitrarySample[CustomerID]
        val insertCustomerIndividualInput = arbitrarySample[InsertCustomerIndividualInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID.value).once(),
        )

        val customerIndividualDetailsRowExpected = CustomerIndividualDetailsRow(
          organizationID,
          customerID,
          insertCustomerIndividualInput.fullName,
          insertCustomerIndividualInput.emails,
          insertCustomerIndividualInput.phoneNumbers,
          insertCustomerIndividualInput.addresses,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        customerBookRepository.insertCustomerIndividual(organizationID, insertCustomerIndividualInput).zioValue shouldBe
          customerIndividualDetailsRowExpected

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRowExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 1
      }

      "fail with a UniqueConstraintViolation when the full name already exists, rolling back the customer row" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val customerID1                   = arbitrarySample[CustomerID]
        val customerID2                   = arbitrarySample[CustomerID]
        val insertCustomerIndividualInput = arbitrarySample[InsertCustomerIndividualInput]

        customerID1 shouldNot equal(customerID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID2.value).once(),
        )

        customerBookRepository.insertCustomerIndividual(organizationID, insertCustomerIndividualInput).zioValue

        val serviceError =
          customerBookRepository.insertCustomerIndividual(organizationID, insertCustomerIndividualInput).zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A customer with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
          List(customerID1)
      }
    }

    "insertCustomerBusiness" should {
      "insert a customer of type Business, its details, and any inline contacts in one transaction" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerID                   = arbitrarySample[CustomerID]
        val customerBusinessContactID    = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput = arbitrarySample[CustomerBusinessContactInput]
        val insertCustomerBusinessInput  =
          arbitrarySample[InsertCustomerBusinessInput].copy(customerBusinessContacts =
            List(customerBusinessContactInput)
          )

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        val customerBusinessDetailsRowExpected = CustomerBusinessDetailsRow(
          organizationID,
          customerID,
          insertCustomerBusinessInput.businessName,
          insertCustomerBusinessInput.taxID,
          insertCustomerBusinessInput.emails,
          insertCustomerBusinessInput.phoneNumbers,
          insertCustomerBusinessInput.addresses,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val customerBusinessContactRowExpected = CustomerBusinessContactRow(
          organizationID,
          customerID,
          customerBusinessContactID,
          customerBusinessContactInput.fullName,
          customerBusinessContactInput.role,
          customerBusinessContactInput.email,
          customerBusinessContactInput.phoneNumber,
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
          isArchived = false,
        )
        val customerBusinessInsertRowExpected: CustomerBusinessInsertRow = (
          customerBusinessDetailsRow = customerBusinessDetailsRowExpected,
          customerBusinessContactRows = List(customerBusinessContactRowExpected),
        )

        customerBookRepository.insertCustomerBusiness(organizationID, insertCustomerBusinessInput).zioValue shouldBe
          customerBusinessInsertRowExpected

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRowExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 1

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRowExpected)
      }

      "fail with a UniqueConstraintViolation when the business name already exists, rolling back the customer row" in new TestContext {
        val organizationID              = arbitrarySample[OrganizationID]
        val customerID1                 = arbitrarySample[CustomerID]
        val customerID2                 = arbitrarySample[CustomerID]
        val insertCustomerBusinessInput =
          arbitrarySample[InsertCustomerBusinessInput].copy(customerBusinessContacts = List.empty)

        customerID1 shouldNot equal(customerID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID2.value).once(),
        )

        customerBookRepository.insertCustomerBusiness(organizationID, insertCustomerBusinessInput).zioValue

        val serviceError =
          customerBookRepository.insertCustomerBusiness(organizationID, insertCustomerBusinessInput).zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A customer with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
          List(customerID1)
      }
    }

    "insertCustomers" should {
      "insert individuals and businesses in a single transaction" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val customerIDIndividual          = arbitrarySample[CustomerID]
        val customerIDBusiness            = arbitrarySample[CustomerID]
        val insertCustomerIndividualInput = arbitrarySample[InsertCustomerIndividualInput]
        val insertCustomerBusinessInput   =
          arbitrarySample[InsertCustomerBusinessInput].copy(customerBusinessContacts = List.empty)

        customerIDIndividual shouldNot equal(customerIDBusiness)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerIDIndividual.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerIDBusiness.value).once(),
        )

        val customerIndividualDetailsRowExpected = CustomerIndividualDetailsRow(
          organizationID,
          customerIDIndividual,
          insertCustomerIndividualInput.fullName,
          insertCustomerIndividualInput.emails,
          insertCustomerIndividualInput.phoneNumbers,
          insertCustomerIndividualInput.addresses,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val customerBusinessDetailsRowExpected = CustomerBusinessDetailsRow(
          organizationID,
          customerIDBusiness,
          insertCustomerBusinessInput.businessName,
          insertCustomerBusinessInput.taxID,
          insertCustomerBusinessInput.emails,
          insertCustomerBusinessInput.phoneNumbers,
          insertCustomerBusinessInput.addresses,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        val insertCustomersResultExpected: InsertCustomersResult = (
          customerIndividualDetailsRows = List(customerIndividualDetailsRowExpected),
          customerBusinessInsertRows = List(
            (
              customerBusinessDetailsRow = customerBusinessDetailsRowExpected,
              customerBusinessContactRows = List.empty,
            )
          ),
        )

        customerBookRepository
          .insertCustomers(organizationID, List(insertCustomerIndividualInput), List(insertCustomerBusinessInput))
          .zioValue shouldBe insertCustomersResultExpected

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRowExpected)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRowExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 2

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe empty
      }
    }

    "updateCustomerIndividual" should {
      "update the details row in place and return the updated row" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            isArchived = false,
            addresses = arbitrarySample[CustomerAddressEntry](customerAddressEntriesCount).toList,
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerFullNameUpdate         = arbitrarySample[CustomerFullName]
        val customerEmailEntryInputsUpdate = arbitrarySample[List[CustomerEmailEntryInput]]
        val customerAddressEntriesUpdate   = arbitrarySample[CustomerAddressEntry](customerAddressEntriesCount).toList

        customerAddressEntriesUpdate shouldNot equal(customerIndividualDetailsRow.addresses)

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val customerIndividualDetailsRowUpdated = customerBookRepository
          .updateCustomerIndividual(
            customerIndividualDetailsRow.organizationID,
            customerIndividualDetailsRow.customerID,
            fullNameOptUpdate = Some(customerFullNameUpdate),
            emailsOptUpdate = Some(customerEmailEntryInputsUpdate),
            addressesOptUpdate = Some(customerAddressEntriesUpdate),
          )
          .zioValue

        customerIndividualDetailsRowUpdated shouldBe Some(
          customerIndividualDetailsRow.copy(
            fullName = customerFullNameUpdate,
            emails = customerEmailEntryInputsUpdate,
            addresses = customerAddressEntriesUpdate,
            updatedAt = UpdatedAt(instantNow),
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          customerIndividualDetailsRowUpdated.toList

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 1
      }

      "silently do nothing and return None when the individual is archived" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = true)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository
          .updateCustomerIndividual(
            customerIndividualDetailsRow.organizationID,
            customerIndividualDetailsRow.customerID,
            fullNameOptUpdate = Some(arbitrarySample[CustomerFullName]),
          )
          .zioValue shouldBe None

        // The archived row is left untouched.
        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRow)
      }
    }

    "updateCustomerBusiness" should {
      "update the details row in place and return the updated row" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            isArchived = false,
            addresses = arbitrarySample[CustomerAddressEntry](customerAddressEntriesCount).toList,
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val customerBusinessNameUpdate = arbitrarySample[CustomerBusinessName]

        customerBusinessDetailsRow.addresses shouldNot equal(Nil)

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val customerBusinessDetailsRowUpdated = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(customerBusinessNameUpdate),
            addressesOptUpdate = Some(Nil),
          )
          .zioValue

        customerBusinessDetailsRowUpdated shouldBe Some(
          customerBusinessDetailsRow.copy(
            businessName = customerBusinessNameUpdate,
            addresses = Nil,
            updatedAt = UpdatedAt(instantNow),
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe
          customerBusinessDetailsRowUpdated.toList

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 1
      }

      "update the business and contacts, insert new contacts, and archive contacts atomically" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessDetailsRowOther = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          isArchived = false,
        )
        val customerBusinessDetailsRowForeign = arbitrarySample[CustomerBusinessDetailsRow].copy(
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowUpdate = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowArchive = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowUntouched = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowUpdateSecond = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(arbitrarySample[CustomerEmail]),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )
        val customerBusinessContactRowOther = customerBusinessContactRowUpdate.copy(
          customerID = customerBusinessDetailsRowOther.customerID
        )
        val customerBusinessContactRowForeign = customerBusinessContactRowUpdate.copy(
          organizationID = customerBusinessDetailsRowForeign.organizationID
        )
        val updateCustomerBusinessContactInputSecond = UpdateCustomerBusinessContactInput(
          customerBusinessContactRowUpdateSecond.customerBusinessContactID,
          Some(arbitrarySample[CustomerFullName]),
          Some(None),
          Some(None),
          Some(None),
        )
        val insertCustomerBusinessInput        = arbitrarySample[InsertCustomerBusinessInput]
        val customerTaxID                      = arbitrarySample[CustomerTaxID]
        val customerEmail                      = arbitrarySample[CustomerEmail]
        val customerPhoneNumber                = arbitrarySample[CustomerPhoneNumber]
        val updateCustomerBusinessContactInput = arbitrarySample[UpdateCustomerBusinessContactInput].copy(
          customerBusinessContactID = customerBusinessContactRowUpdate.customerBusinessContactID,
          fullName = Some(arbitrarySample[CustomerFullName]),
          role = Some(Some(arbitrarySample[CustomerBusinessContactRole])),
          email = Some(Some(customerEmail)),
          phoneNumber = Some(Some(customerPhoneNumber)),
        )
        val customerBusinessContactInput = arbitrarySample[CustomerBusinessContactInput].copy(
          email = Some(customerEmail),
          phoneNumber = Some(customerPhoneNumber),
        )
        val customerBusinessContactID          = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessDetailsRowExpected = customerBusinessDetailsRow.copy(
          businessName = insertCustomerBusinessInput.businessName,
          emails = insertCustomerBusinessInput.emails,
          taxID = Some(customerTaxID),
          phoneNumbers = insertCustomerBusinessInput.phoneNumbers,
          addresses = insertCustomerBusinessInput.addresses,
          updatedAt = UpdatedAt(instantNow),
        )
        val customerBusinessContactRowExpectedUpdated = customerBusinessContactRowUpdate.copy(
          fullName = updateCustomerBusinessContactInput.fullName.value,
          role = updateCustomerBusinessContactInput.role.value,
          email = Some(customerEmail),
          phoneNumber = Some(customerPhoneNumber),
          updatedAt = UpdatedAt(instantNow),
        )
        val customerBusinessContactRowExpectedUpdatedSecond = customerBusinessContactRowUpdateSecond.copy(
          fullName = updateCustomerBusinessContactInputSecond.fullName.value,
          role = None,
          email = None,
          phoneNumber = None,
          updatedAt = UpdatedAt(instantNow),
        )
        val customerBusinessContactRowExpectedInserted = customerBusinessContactRowUpdate.copy(
          customerBusinessContactID = customerBusinessContactID,
          fullName = customerBusinessContactInput.fullName,
          role = customerBusinessContactInput.role,
          email = customerBusinessContactInput.email,
          phoneNumber = customerBusinessContactInput.phoneNumber,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
          isArchived = false,
        )

        List(
          customerBusinessContactRowUpdate.customerBusinessContactID,
          customerBusinessContactRowArchive.customerBusinessContactID,
          customerBusinessContactRowUntouched.customerBusinessContactID,
          customerBusinessContactID,
          customerBusinessContactRowUpdateSecond.customerBusinessContactID,
        ).distinct should have size 5
        customerBusinessDetailsRowOther.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        customerBusinessDetailsRowForeign.organizationID shouldNot equal(customerBusinessDetailsRow.organizationID)
        customerBusinessContactRowOther.customerBusinessContactID shouldBe customerBusinessContactRowUpdate.customerBusinessContactID
        customerBusinessContactRowForeign.customerBusinessContactID shouldBe customerBusinessContactRowUpdate.customerBusinessContactID
        customerBusinessContactRowExpectedUpdatedSecond shouldNot equal(customerBusinessContactRowUpdateSecond)
        customerBusinessDetailsRowExpected shouldNot equal(customerBusinessDetailsRow)
        customerBusinessContactRowExpectedUpdated shouldNot equal(customerBusinessContactRowUpdate)

        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRows(
              List(customerBusinessDetailsRow, customerBusinessDetailsRowOther, customerBusinessDetailsRowForeign)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(
              List(
                customerBusinessContactRowUpdate,
                customerBusinessContactRowArchive,
                customerBusinessContactRowUntouched,
                customerBusinessContactRowUpdateSecond,
                customerBusinessContactRowOther,
                customerBusinessContactRowForeign,
              )
            )
          )
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(insertCustomerBusinessInput.businessName),
            emailsOptUpdate = Some(insertCustomerBusinessInput.emails),
            taxIDOptUpdate = Some(Some(customerTaxID)),
            phoneNumbersOptUpdate = Some(insertCustomerBusinessInput.phoneNumbers),
            addressesOptUpdate = Some(insertCustomerBusinessInput.addresses),
            updateCustomerBusinessContactInputs =
              List(updateCustomerBusinessContactInput, updateCustomerBusinessContactInputSecond),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = List(customerBusinessContactRowArchive.customerBusinessContactID),
          )
          .zioValue shouldBe Some(customerBusinessDetailsRowExpected)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(
          customerBusinessDetailsRowExpected,
          customerBusinessDetailsRowOther,
          customerBusinessDetailsRowForeign,
        )
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs List(
          customerBusinessContactRowExpectedUpdated,
          customerBusinessContactRowArchive.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)),
          customerBusinessContactRowUntouched,
          customerBusinessContactRowExpectedInserted,
          customerBusinessContactRowExpectedUpdatedSecond,
          customerBusinessContactRowOther,
          customerBusinessContactRowForeign,
        )
      }

      "clear nullable business and contact values while retaining names and omitted lists" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          isArchived = false,
          taxID = Some(arbitrarySample[CustomerTaxID]),
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(arbitrarySample[CustomerEmail]),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )
        val updateCustomerBusinessContactInput = UpdateCustomerBusinessContactInput(
          customerBusinessContactRow.customerBusinessContactID,
          None,
          Some(None),
          Some(None),
          Some(None),
        )
        val customerBusinessDetailsRowExpected =
          customerBusinessDetailsRow.copy(taxID = None, updatedAt = UpdatedAt(instantNow))
        val customerBusinessContactRowExpected = customerBusinessContactRow.copy(
          role = None,
          email = None,
          phoneNumber = None,
          updatedAt = UpdatedAt(instantNow),
        )

        customerBusinessDetailsRowExpected shouldNot equal(customerBusinessDetailsRow)
        customerBusinessContactRowExpected shouldNot equal(customerBusinessContactRow)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
          )
          .zioValue shouldBe Some(customerBusinessDetailsRowExpected)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRowExpected)
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRowExpected)
      }

      "clear supplied empty business lists while retaining omitted tax and contact fields" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          isArchived = false,
          taxID = Some(arbitrarySample[CustomerTaxID]),
          emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses = List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(arbitrarySample[CustomerEmail]),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )
        val customerFullName                   = arbitrarySample[CustomerFullName]
        val updateCustomerBusinessContactInput = UpdateCustomerBusinessContactInput(
          customerBusinessContactRow.customerBusinessContactID,
          Some(customerFullName),
          None,
          None,
          None,
        )
        val customerBusinessDetailsRowExpected = customerBusinessDetailsRow.copy(
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          updatedAt = UpdatedAt(instantNow),
        )
        val customerBusinessContactRowExpected =
          customerBusinessContactRow.copy(fullName = customerFullName, updatedAt = UpdatedAt(instantNow))

        customerBusinessDetailsRowExpected shouldNot equal(customerBusinessDetailsRow)
        customerBusinessContactRowExpected shouldNot equal(customerBusinessContactRow)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            emailsOptUpdate = Some(Nil),
            phoneNumbersOptUpdate = Some(Nil),
            addressesOptUpdate = Some(Nil),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
          )
          .zioValue shouldBe Some(customerBusinessDetailsRowExpected)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRowExpected)
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRowExpected)
      }

      "fail with an UnexpectedError and roll back the business when an update contact is missing" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactIDInvalid    = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessDetailsRows         = List(customerBusinessDetailsRow)
        val customerBusinessContactRows         = List(customerBusinessContactRow)
        val updateCustomerBusinessContactInputs = List(
          UpdateCustomerBusinessContactInput(
            customerBusinessContactRow.customerBusinessContactID,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
          UpdateCustomerBusinessContactInput(
            customerBusinessContactIDInvalid,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
        )
        val customerBusinessContactIDInsert = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput    = arbitrarySample[CustomerBusinessContactInput]

        customerBusinessContactIDInvalid shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactIDInsert.value).once(),
        )

        val serviceError = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = updateCustomerBusinessContactInputs,
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update customer business with ID: [${customerBusinessDetailsRow.customerID}]. Some customer business contact IDs do not exist, belong to another customer business, or are archived. Requested: [${updateCustomerBusinessContactInputs.map(_.customerBusinessContactID)}], Found: [${List(customerBusinessContactRow)}]"
        )
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRows
      }

      "fail with an UnexpectedError and roll back the business when an update contact is archived" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowInvalid = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = true,
        )
        val customerBusinessContactIDInvalid    = customerBusinessContactRowInvalid.customerBusinessContactID
        val customerBusinessDetailsRows         = List(customerBusinessDetailsRow)
        val customerBusinessContactRows         = List(customerBusinessContactRow, customerBusinessContactRowInvalid)
        val updateCustomerBusinessContactInputs = List(
          UpdateCustomerBusinessContactInput(
            customerBusinessContactRow.customerBusinessContactID,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
          UpdateCustomerBusinessContactInput(
            customerBusinessContactIDInvalid,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
        )
        val customerBusinessContactIDInsert = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput    = arbitrarySample[CustomerBusinessContactInput]

        customerBusinessContactIDInvalid shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactIDInsert.value).once(),
        )

        val serviceError = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = updateCustomerBusinessContactInputs,
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update customer business with ID: [${customerBusinessDetailsRow.customerID}]. Some customer business contact IDs do not exist, belong to another customer business, or are archived. Requested: [${updateCustomerBusinessContactInputs.map(_.customerBusinessContactID)}], Found: [${List(customerBusinessContactRow)}]"
        )
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRows
      }

      "fail with an UnexpectedError and roll back the business when an update contact is another business" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessDetailsRowForeign = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          isArchived = false,
        )
        val customerBusinessContactRowInvalid = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRowForeign.organizationID,
          customerID = customerBusinessDetailsRowForeign.customerID,
          isArchived = false,
        )
        val customerBusinessContactIDInvalid = customerBusinessContactRowInvalid.customerBusinessContactID
        val customerBusinessDetailsRows      = List(customerBusinessDetailsRow, customerBusinessDetailsRowForeign)
        val customerBusinessContactRows      = List(customerBusinessContactRow, customerBusinessContactRowInvalid)
        customerBusinessDetailsRowForeign.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        val updateCustomerBusinessContactInputs = List(
          UpdateCustomerBusinessContactInput(
            customerBusinessContactRow.customerBusinessContactID,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
          UpdateCustomerBusinessContactInput(
            customerBusinessContactIDInvalid,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
        )
        val customerBusinessContactIDInsert = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput    = arbitrarySample[CustomerBusinessContactInput]

        customerBusinessContactIDInvalid shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactIDInsert.value).once(),
        )

        val serviceError = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = updateCustomerBusinessContactInputs,
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update customer business with ID: [${customerBusinessDetailsRow.customerID}]. Some customer business contact IDs do not exist, belong to another customer business, or are archived. Requested: [${updateCustomerBusinessContactInputs.map(_.customerBusinessContactID)}], Found: [${List(customerBusinessContactRow)}]"
        )
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRows
      }

      "fail with an UnexpectedError and roll back the business when an update contact is another organization" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessDetailsRowForeign = arbitrarySample[CustomerBusinessDetailsRow].copy(
          customerID = customerBusinessDetailsRow.customerID,
          organizationID = arbitrarySample[OrganizationID],
          isArchived = false,
        )
        val customerBusinessContactRowInvalid = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRowForeign.organizationID,
          customerID = customerBusinessDetailsRowForeign.customerID,
          isArchived = false,
        )
        val customerBusinessContactIDInvalid = customerBusinessContactRowInvalid.customerBusinessContactID
        val customerBusinessDetailsRows      = List(customerBusinessDetailsRow, customerBusinessDetailsRowForeign)
        val customerBusinessContactRows      = List(customerBusinessContactRow, customerBusinessContactRowInvalid)
        customerBusinessDetailsRowForeign.organizationID shouldNot equal(customerBusinessDetailsRow.organizationID)
        val updateCustomerBusinessContactInputs = List(
          UpdateCustomerBusinessContactInput(
            customerBusinessContactRow.customerBusinessContactID,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
          UpdateCustomerBusinessContactInput(
            customerBusinessContactIDInvalid,
            Some(arbitrarySample[CustomerFullName]),
            None,
            None,
            None,
          ),
        )
        val customerBusinessContactIDInsert = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput    = arbitrarySample[CustomerBusinessContactInput]

        customerBusinessContactIDInvalid shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactIDInsert.value).once(),
        )

        val serviceError = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = updateCustomerBusinessContactInputs,
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update customer business with ID: [${customerBusinessDetailsRow.customerID}]. Some customer business contact IDs do not exist, belong to another customer business, or are archived. Requested: [${updateCustomerBusinessContactInputs.map(_.customerBusinessContactID)}], Found: [${List(customerBusinessContactRow)}]"
        )
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRows
      }

      "return None without touching contacts when the business target is missing" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerID                 = arbitrarySample[CustomerID]
        val customerBusinessContactIDs = List(arbitrarySample[CustomerBusinessContactID])

        val customerBusinessContactID          = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput       = arbitrarySample[CustomerBusinessContactInput]
        val updateCustomerBusinessContactInput = arbitrarySample[UpdateCustomerBusinessContactInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        customerBookRepository
          .updateCustomerBusiness(
            organizationID,
            customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = customerBusinessContactIDs,
          )
          .zioValue shouldBe None

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "return None without touching contacts when the business target is archived" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = true)
        val organizationID             = customerBusinessDetailsRow.organizationID
        val customerID                 = customerBusinessDetailsRow.customerID
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerID,
          isArchived = false,
        )
        val customerBusinessContactIDs = List(customerBusinessContactRow.customerBusinessContactID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBusinessContactID          = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput       = arbitrarySample[CustomerBusinessContactInput]
        val updateCustomerBusinessContactInput = arbitrarySample[UpdateCustomerBusinessContactInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        customerBookRepository
          .updateCustomerBusiness(
            organizationID,
            customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = customerBusinessContactIDs,
          )
          .zioValue shouldBe None

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "return None without touching contacts when the business target is another organization" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val organizationID             = arbitrarySample[OrganizationID]
        val customerID                 = customerBusinessDetailsRow.customerID
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerID,
          isArchived = false,
        )
        val customerBusinessContactIDs = List(customerBusinessContactRow.customerBusinessContactID)

        organizationID shouldNot equal(customerBusinessDetailsRow.organizationID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBusinessContactID          = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput       = arbitrarySample[CustomerBusinessContactInput]
        val updateCustomerBusinessContactInput = arbitrarySample[UpdateCustomerBusinessContactInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        customerBookRepository
          .updateCustomerBusiness(
            organizationID,
            customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = customerBusinessContactIDs,
          )
          .zioValue shouldBe None

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "return None without touching contacts when the business target is an individual" in new TestContext {
        val customerIndividualDetailsRow =
          arbitrarySample[CustomerIndividualDetailsRow].copy(isArchived = false)
        val organizationID             = customerIndividualDetailsRow.organizationID
        val customerID                 = customerIndividualDetailsRow.customerID
        val customerBusinessContactIDs = List(arbitrarySample[CustomerBusinessContactID])

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerBusinessContactID          = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput       = arbitrarySample[CustomerBusinessContactInput]
        val updateCustomerBusinessContactInput = arbitrarySample[UpdateCustomerBusinessContactInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        customerBookRepository
          .updateCustomerBusiness(
            organizationID,
            customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = customerBusinessContactIDs,
          )
          .zioValue shouldBe None

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)
        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "archive only matching active contacts and retain archive timestamps on repeated updates" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessDetailsRowOther = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          isArchived = false,
        )
        val customerBusinessDetailsRowForeign = arbitrarySample[CustomerBusinessDetailsRow].copy(
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowArchived = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = true,
        )
        val customerBusinessContactRowOther = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRowOther.organizationID,
          customerID = customerBusinessDetailsRowOther.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowForeign = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRowForeign.organizationID,
          customerID = customerBusinessDetailsRowForeign.customerID,
          isArchived = false,
        )
        val customerBusinessContactIDMissing  = arbitrarySample[CustomerBusinessContactID]
        val archiveCustomerBusinessContactIDs = List(
          customerBusinessContactRow.customerBusinessContactID,
          customerBusinessContactRowArchived.customerBusinessContactID,
          customerBusinessContactRowOther.customerBusinessContactID,
          customerBusinessContactRowForeign.customerBusinessContactID,
          customerBusinessContactIDMissing,
        )
        val customerBusinessDetailsRows =
          List(customerBusinessDetailsRow, customerBusinessDetailsRowOther, customerBusinessDetailsRowForeign)
        val customerBusinessContactRows = List(
          customerBusinessContactRow,
          customerBusinessContactRowArchived,
          customerBusinessContactRowOther,
          customerBusinessContactRowForeign,
        )
        val archiveRepeatDelayMillis            = 1L
        val instantNowNext                      = instantNow.plusMillis(archiveRepeatDelayMillis)
        val customerBusinessContactRowsExpected = List(
          customerBusinessContactRow.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)),
          customerBusinessContactRowArchived,
          customerBusinessContactRowOther,
          customerBusinessContactRowForeign,
        )

        customerBusinessDetailsRowOther.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        customerBusinessDetailsRowForeign.organizationID shouldNot equal(customerBusinessDetailsRow.organizationID)
        archiveCustomerBusinessContactIDs.distinct should have size archiveCustomerBusinessContactIDs.size

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNowNext).once(),
        )

        customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            archiveCustomerBusinessContactIDs = archiveCustomerBusinessContactIDs,
          )
          .zioValue shouldBe Some(customerBusinessDetailsRow.copy(updatedAt = UpdatedAt(instantNow)))

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRowsExpected

        customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            archiveCustomerBusinessContactIDs = archiveCustomerBusinessContactIDs,
          )
          .zioValue shouldBe Some(customerBusinessDetailsRow.copy(updatedAt = UpdatedAt(instantNowNext)))

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRowsExpected
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(
          customerBusinessDetailsRow.copy(updatedAt = UpdatedAt(instantNowNext)),
          customerBusinessDetailsRowOther,
          customerBusinessDetailsRowForeign,
        )
      }

      "fail with a UniqueConstraintViolation and roll back earlier business and contact changes when an inserted contact ID collides" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowArchive = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactInput       = arbitrarySample[CustomerBusinessContactInput]
        val updateCustomerBusinessContactInput = UpdateCustomerBusinessContactInput(
          customerBusinessContactRow.customerBusinessContactID,
          Some(arbitrarySample[CustomerFullName]),
          Some(None),
          Some(None),
          Some(None),
        )
        val customerBusinessContactRows = List(customerBusinessContactRow, customerBusinessContactRowArchive)

        customerBusinessContactRow.customerBusinessContactID shouldNot equal(
          customerBusinessContactRowArchive.customerBusinessContactID
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(customerBusinessContactRow.customerBusinessContactID.value)
            .once(),
        )

        val serviceError = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(arbitrarySample[CustomerBusinessName]),
            taxIDOptUpdate = Some(None),
            updateCustomerBusinessContactInputs = List(updateCustomerBusinessContactInput),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = List(customerBusinessContactRowArchive.customerBusinessContactID),
          )
          .zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A unique constraint was violated: [customer_business_contact_pkey]"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRows
      }

      "fail with a UniqueConstraintViolation when the business name is taken and retain all existing contacts" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessDetailsRowExisting = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactID    = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput = arbitrarySample[CustomerBusinessContactInput]
        val customerBusinessDetailsRows  = List(customerBusinessDetailsRow, customerBusinessDetailsRowExisting)

        customerBusinessDetailsRow.businessName shouldNot equal(customerBusinessDetailsRowExisting.businessName)
        customerBusinessDetailsRow.customerID shouldNot equal(customerBusinessDetailsRowExisting.customerID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        val serviceError = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(customerBusinessDetailsRowExisting.businessName),
            insertCustomerBusinessContactInputs = List(customerBusinessContactInput),
            archiveCustomerBusinessContactIDs = List(customerBusinessContactRow.customerBusinessContactID),
          )
          .zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A customer with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

    }

    "archiveCustomer" should {
      "archive an active individual customer, setting its archive flag to true and returning its id" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository
          .archiveCustomer(customerIndividualDetailsRow.organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe Some(customerIndividualDetailsRow.customerID)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRow.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)))
      }

      "archive an active business customer, setting its archive flag to true and returning its id" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository
          .archiveCustomer(customerBusinessDetailsRow.organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe Some(customerBusinessDetailsRow.customerID)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe
          List(customerBusinessDetailsRow.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)))
      }

      "return None when no customer matches the id" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository.archiveCustomer(organizationID, customerID).zioValue shouldBe None

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe empty
      }

      "return None and leave the row untouched when the customer is already archived" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = true)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        customerBookRepository
          .archiveCustomer(customerIndividualDetailsRow.organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe None

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRow)
      }

      // uq_customer_name is partial on is_archived = false, so archiving frees the name: an organization
      // can hold any number of archived same-name customers of one kind alongside a single active one.
      "allow archiving multiple individuals that share a name, alongside one that stays active" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val sharedName     = CustomerFullName.assume("Shared Name")
        val customerID1    = arbitrarySample[CustomerID]
        val customerID2    = arbitrarySample[CustomerID]
        val customerID3    = arbitrarySample[CustomerID]

        List(customerID1, customerID2, customerID3).distinct should have size 3

        val customerIndividualDetailsRow1 = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            customerID = customerID1,
            fullName = sharedName,
            isArchived = false,
          )
        val customerIndividualDetailsRow2 = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            customerID = customerID2,
            fullName = sharedName,
            isArchived = false,
          )
        val customerIndividualDetailsRow3 = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            customerID = customerID3,
            fullName = sharedName,
            isArchived = false,
          )

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).twice()

        // Each active same-name insert only succeeds because the previous one was archived first.
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow1))
          .zioValue
        customerBookRepository.archiveCustomer(organizationID, customerID1).zioValue shouldBe Some(customerID1)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow2))
          .zioValue
        customerBookRepository.archiveCustomer(organizationID, customerID2).zioValue shouldBe Some(customerID2)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow3))
          .zioValue

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue should
          contain theSameElementsAs List(
            customerIndividualDetailsRow1.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)),
            customerIndividualDetailsRow2.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)),
            customerIndividualDetailsRow3,
          )
        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 3
      }

      "allow archiving multiple businesses that share a name, alongside one that stays active" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val sharedName     = CustomerBusinessName.assume("Shared Name")
        val customerID1    = arbitrarySample[CustomerID]
        val customerID2    = arbitrarySample[CustomerID]
        val customerID3    = arbitrarySample[CustomerID]

        List(customerID1, customerID2, customerID3).distinct should have size 3

        val customerBusinessDetailsRow1 = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationID,
            customerID = customerID1,
            businessName = sharedName,
            isArchived = false,
          )
        val customerBusinessDetailsRow2 = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationID,
            customerID = customerID2,
            businessName = sharedName,
            isArchived = false,
          )
        val customerBusinessDetailsRow3 = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationID,
            customerID = customerID3,
            businessName = sharedName,
            isArchived = false,
          )

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).twice()

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow1))
          .zioValue
        customerBookRepository.archiveCustomer(organizationID, customerID1).zioValue shouldBe Some(customerID1)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow2))
          .zioValue
        customerBookRepository.archiveCustomer(organizationID, customerID2).zioValue shouldBe Some(customerID2)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow3))
          .zioValue

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue should
          contain theSameElementsAs List(
            customerBusinessDetailsRow1.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)),
            customerBusinessDetailsRow2.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)),
            customerBusinessDetailsRow3,
          )
        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 3
      }

      "archive both an individual and a business that share a name" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val sharedName     = "Shared Name"

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            fullName = CustomerFullName.assume(sharedName),
            isArchived = false,
          )
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationID,
            businessName = CustomerBusinessName.assume(sharedName),
            isArchived = false,
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).twice()

        customerBookRepository
          .archiveCustomer(organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe Some(customerIndividualDetailsRow.customerID)
        customerBookRepository
          .archiveCustomer(organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe Some(customerBusinessDetailsRow.customerID)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRow.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)))
        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe
          List(customerBusinessDetailsRow.copy(isArchived = true, updatedAt = UpdatedAt(instantNow)))
      }
    }

    "getCustomerIndividual" should {
      "return the details row when it exists" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        customerBookRepository
          .getCustomerIndividual(customerIndividualDetailsRow.organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe Some(customerIndividualDetailsRow)
      }

      "return None and retain the complete row when the individual is archived" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = true)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        customerBookRepository
          .getCustomerIndividual(customerIndividualDetailsRow.organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe None

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)
      }

      "return None when it does not exist" in new TestContext {
        customerBookRepository
          .getCustomerIndividual(arbitrarySample[OrganizationID], arbitrarySample[CustomerID])
          .zioValue shouldBe None
      }
    }

    "getCustomerBusiness" should {
      "return the business with an empty contact list when it has no contacts" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        customerBookRepository
          .getCustomerBusiness(customerBusinessDetailsRow.organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe Some(CustomerBusinessWithContactsRow(customerBusinessDetailsRow, Nil))
      }

      "return None when it does not exist" in new TestContext {
        customerBookRepository
          .getCustomerBusiness(arbitrarySample[OrganizationID], arbitrarySample[CustomerID])
          .zioValue shouldBe None
      }
      "return only active contacts for the business ordered by creation time then contact ID" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessDetailsRowOtherBusiness = customerBusinessDetailsRow.copy(
          customerID = arbitrarySample[CustomerID],
          businessName = arbitrarySample[CustomerBusinessName],
        )
        val customerBusinessDetailsRowOtherOrganization = customerBusinessDetailsRow.copy(
          organizationID = arbitrarySample[OrganizationID]
        )
        val customerBusinessContactRowsByID = List
          .fill(3)(arbitrarySample[CustomerBusinessContactRow])
          .sortBy(_.customerBusinessContactID.value.toString)
        val customerBusinessContactRowLater = customerBusinessContactRowsByID.head.copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          createdAt = CreatedAt(instantNow.plusSeconds(1)),
          isArchived = false,
        )
        val customerBusinessContactRowsEarlier = customerBusinessContactRowsByID.tail.map(
          _.copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            createdAt = CreatedAt(instantNow),
            isArchived = false,
          )
        )
        val customerBusinessContactRowsExpected = customerBusinessContactRowsEarlier :+ customerBusinessContactRowLater
        val customerBusinessContactRowArchived  = customerBusinessContactRowLater.copy(
          customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
          isArchived = true,
        )
        val customerBusinessContactRowOtherBusiness = customerBusinessContactRowLater.copy(
          customerID = customerBusinessDetailsRowOtherBusiness.customerID
        )
        val customerBusinessContactRowOtherOrganization = customerBusinessContactRowLater.copy(
          organizationID = customerBusinessDetailsRowOtherOrganization.organizationID
        )
        val customerBusinessContactRows =
          customerBusinessContactRowLater :: customerBusinessContactRowsEarlier.reverse :::
            List(
              customerBusinessContactRowArchived,
              customerBusinessContactRowOtherBusiness,
              customerBusinessContactRowOtherOrganization,
            )

        customerBusinessDetailsRowOtherBusiness.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        customerBusinessDetailsRowOtherBusiness.businessName shouldNot equal(customerBusinessDetailsRow.businessName)
        customerBusinessDetailsRowOtherOrganization.organizationID shouldNot equal(
          customerBusinessDetailsRow.organizationID
        )
        customerBusinessContactRowsExpected.map(_.customerBusinessContactID).distinct.size shouldBe 3
        customerBusinessContactRowsExpected.map(
          _.customerBusinessContactID
        ) should not contain customerBusinessContactRowArchived.customerBusinessContactID
        customerBusinessContactRowOtherBusiness.customerBusinessContactID shouldBe customerBusinessContactRowLater.customerBusinessContactID
        customerBusinessContactRowOtherOrganization.customerBusinessContactID shouldBe customerBusinessContactRowLater.customerBusinessContactID

        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRows(
              List(
                customerBusinessDetailsRow,
                customerBusinessDetailsRowOtherBusiness,
                customerBusinessDetailsRowOtherOrganization,
              )
            )
          )
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
          .zioValue

        customerBookRepository
          .getCustomerBusiness(customerBusinessDetailsRow.organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe Some(
          CustomerBusinessWithContactsRow(customerBusinessDetailsRow, customerBusinessContactRowsExpected)
        )
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessContactRows
      }

      "return None and retain the complete business and contacts when the business is archived" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = true)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        customerBookRepository
          .getCustomerBusiness(customerBusinessDetailsRow.organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe None

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }
      "return the business with an empty contact list when all its contacts are archived" in new TestContext {
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = true,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        customerBookRepository
          .getCustomerBusiness(customerBusinessDetailsRow.organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe Some(CustomerBusinessWithContactsRow(customerBusinessDetailsRow, Nil))
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "return None when the customer is an individual" in new TestContext {
        val customerIndividualDetailsRow =
          arbitrarySample[CustomerIndividualDetailsRow].copy(isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        customerBookRepository
          .getCustomerBusiness(customerIndividualDetailsRow.organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe None
      }
    }

    "getCustomerSummaries" should {
      "return every active customer in display name order when the requested ID list is empty" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationID,
          fullName = CustomerFullName.either("Alpha Individual").toOption.value,
          isArchived = false,
        )
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationID,
          businessName = CustomerBusinessName.either("Zulu Business").toOption.value,
          isArchived = false,
        )
        val customerIndividualDetailsRowArchived = customerIndividualDetailsRow.copy(
          customerID = arbitrarySample[CustomerID],
          isArchived = true,
        )
        val customerBusinessDetailsRowForeign = customerBusinessDetailsRow.copy(
          organizationID = arbitrarySample[OrganizationID],
          customerID = arbitrarySample[CustomerID],
        )
        val customerIndividualDetailsRows = List(customerIndividualDetailsRowArchived, customerIndividualDetailsRow)
        val customerBusinessDetailsRows   = List(customerBusinessDetailsRowForeign, customerBusinessDetailsRow)

        customerBusinessDetailsRowForeign.organizationID shouldNot equal(organizationID)
        customerIndividualDetailsRowArchived.customerID shouldNot equal(customerIndividualDetailsRow.customerID)
        customerBusinessDetailsRowForeign.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        customerBusinessDetailsRow.customerID shouldNot equal(customerIndividualDetailsRow.customerID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))
          .zioValue

        customerBookRepository.getCustomerSummaries(organizationID, Nil).zioValue shouldBe List(
          CustomerSummaryRow(
            customerIndividualDetailsRow.customerID,
            customerIndividualDetailsRow.fullName.to[CustomerName],
            CustomerType.Individual,
          ),
          CustomerSummaryRow(
            customerBusinessDetailsRow.customerID,
            customerBusinessDetailsRow.businessName.to[CustomerName],
            CustomerType.Business,
          ),
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerIndividualDetailsRows

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
      }

      "return requested active customers once in display name and ID order while skipping unavailable and foreign customers" in new TestContext {
        val customerIDMostSignificantBits    = 0L
        val customerIDBusinessOrdinal        = 1L
        val customerIDIndividualOrdinal      = 2L
        val customerIDIndividualLaterOrdinal = 3L
        val customerIDArchivedOrdinal        = 4L
        val customerIDForeignOrdinal         = 5L
        val customerIDUnknownOrdinal         = 6L
        val customerIDUnrequestedOrdinal     = 7L
        val equalCustomerIDsComparisonResult = 0
        val distinctCustomerIDsCountExpected = 7
        val organizationID                   = arbitrarySample[OrganizationID]
        val customerBusinessDetailsRow       = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationID,
          customerID = CustomerID(new UUID(customerIDMostSignificantBits, customerIDBusinessOrdinal)),
          businessName = CustomerBusinessName.either("ALPHA").toOption.value,
          isArchived = false,
        )
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationID,
          customerID = CustomerID(new UUID(customerIDMostSignificantBits, customerIDIndividualOrdinal)),
          fullName = CustomerFullName.either("alpha").toOption.value,
          isArchived = false,
        )
        val customerIndividualDetailsRowLater = customerIndividualDetailsRow.copy(
          customerID = CustomerID(new UUID(customerIDMostSignificantBits, customerIDIndividualLaterOrdinal)),
          fullName = CustomerFullName.either("Zulu").toOption.value,
        )
        val customerIndividualDetailsRowArchived = customerIndividualDetailsRow.copy(
          customerID = CustomerID(new UUID(customerIDMostSignificantBits, customerIDArchivedOrdinal)),
          isArchived = true,
        )
        val customerBusinessDetailsRowForeign = customerBusinessDetailsRow.copy(
          organizationID = arbitrarySample[OrganizationID],
          customerID = CustomerID(new UUID(customerIDMostSignificantBits, customerIDForeignOrdinal)),
        )
        val customerIDUnknown = CustomerID(new UUID(customerIDMostSignificantBits, customerIDUnknownOrdinal))
        val customerIndividualDetailsRowUnrequested = customerIndividualDetailsRow.copy(
          customerID = CustomerID(new UUID(customerIDMostSignificantBits, customerIDUnrequestedOrdinal)),
          fullName = CustomerFullName.either("Aardvark").toOption.value,
        )
        val customerIndividualDetailsRows = List(
          customerIndividualDetailsRowLater,
          customerIndividualDetailsRowArchived,
          customerIndividualDetailsRowUnrequested,
          customerIndividualDetailsRow,
        )
        val customerBusinessDetailsRows = List(customerBusinessDetailsRowForeign, customerBusinessDetailsRow)
        val customerIDs                 = List(
          customerIndividualDetailsRowLater.customerID,
          customerIndividualDetailsRow.customerID,
          customerBusinessDetailsRow.customerID,
          customerIndividualDetailsRow.customerID,
          customerIndividualDetailsRowArchived.customerID,
          customerBusinessDetailsRowForeign.customerID,
          customerIDUnknown,
        )

        customerIndividualDetailsRow.fullName.value.toLowerCase shouldBe customerBusinessDetailsRow.businessName.value.toLowerCase
        customerBusinessDetailsRow.customerID.value.compareTo(
          customerIndividualDetailsRow.customerID.value
        ) should be < equalCustomerIDsComparisonResult
        customerBusinessDetailsRowForeign.organizationID shouldNot equal(customerBusinessDetailsRow.organizationID)
        (customerIndividualDetailsRows.map(_.customerID) ++ customerBusinessDetailsRows.map(
          _.customerID
        ) :+ customerIDUnknown).distinct.size shouldBe distinctCustomerIDsCountExpected

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
          .zioValue

        customerBookRepository.getCustomerSummaries(organizationID, customerIDs).zioValue shouldBe List(
          CustomerSummaryRow(
            customerBusinessDetailsRow.customerID,
            customerBusinessDetailsRow.businessName.to[CustomerName],
            CustomerType.Business,
          ),
          CustomerSummaryRow(
            customerIndividualDetailsRow.customerID,
            customerIndividualDetailsRow.fullName.to[CustomerName],
            CustomerType.Individual,
          ),
          CustomerSummaryRow(
            customerIndividualDetailsRowLater.customerID,
            customerIndividualDetailsRowLater.fullName.to[CustomerName],
            CustomerType.Individual,
          ),
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerIndividualDetailsRows

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerBusinessDetailsRows
      }

      "return an empty list when no requested customer exists" in new TestContext {
        customerBookRepository
          .getCustomerSummaries(arbitrarySample[OrganizationID], List(arbitrarySample[CustomerID]))
          .zioValue shouldBe Nil
      }
    }

    "isUniqueCustomerName" should {
      "return false for active matching names of both customer kinds without changing stored rows" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerName                 = arbitrarySample[CustomerName]
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationID,
          fullName = customerName.to[CustomerFullName],
          isArchived = false,
        )
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationID,
          businessName = customerName.to[CustomerBusinessName],
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = organizationID,
          customerID = customerBusinessDetailsRow.customerID,
        )

        customerIndividualDetailsRow.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        customerIndividualDetailsRow.fullName.value shouldBe customerBusinessDetailsRow.businessName.value

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        customerBookRepository
          .isUniqueCustomerName(organizationID, customerName, CustomerType.Individual, None)
          .zioValue shouldBe false
        customerBookRepository
          .isUniqueCustomerName(organizationID, customerName, CustomerType.Business, None)
          .zioValue shouldBe false

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(customerIndividualDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessContactRow)
      }

      "return true for an empty customer book without storing or reserving a name" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerName   = arbitrarySample[CustomerName]
        val customerType   = arbitrarySample[CustomerType]

        customerBookRepository
          .isUniqueCustomerName(organizationID, customerName, customerType, None)
          .zioValue shouldBe true

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs Nil
      }

      "return true when matching names belong only to another kind archived customers or another organization" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerName               = arbitrarySample[CustomerName]
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationID,
          businessName = customerName.to[CustomerBusinessName],
          isArchived = false,
        )
        val customerIndividualDetailsRowArchived = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationID,
          fullName = customerName.to[CustomerFullName],
          isArchived = true,
        )
        val customerIndividualDetailsRowForeign = arbitrarySample[CustomerIndividualDetailsRow].copy(
          fullName = customerName.to[CustomerFullName],
          isArchived = false,
        )
        val customerIndividualDetailsRows =
          List(customerIndividualDetailsRowArchived, customerIndividualDetailsRowForeign)

        customerIndividualDetailsRowForeign.organizationID shouldNot equal(organizationID)
        customerIndividualDetailsRowArchived.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))
          .zioValue

        customerBookRepository
          .isUniqueCustomerName(organizationID, customerName, CustomerType.Individual, None)
          .zioValue shouldBe true

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs customerIndividualDetailsRows

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs Nil
      }

      "return true when the matching active customer is the excluded customer and retain its contacts" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)
        val customerName               = customerBusinessDetailsRow.businessName.to[CustomerName]
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        customerBookRepository
          .isUniqueCustomerName(
            customerBusinessDetailsRow.organizationID,
            customerName,
            CustomerType.Business,
            Some(customerBusinessDetailsRow.customerID),
          )
          .zioValue shouldBe true

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessContactRow)
      }

      "return false when the excluded ID is unrelated to the active matching customer" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(isArchived = false)
        val customerName                 = customerIndividualDetailsRow.fullName.to[CustomerName]
        val customerIDUnrelated          = arbitrarySample[CustomerID]

        customerIDUnrelated shouldNot equal(customerIndividualDetailsRow.customerID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        customerBookRepository
          .isUniqueCustomerName(
            customerIndividualDetailsRow.organizationID,
            customerName,
            CustomerType.Individual,
            Some(customerIDUnrelated),
          )
          .zioValue shouldBe false

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(customerIndividualDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs Nil
      }

      "return true for a differently cased name because name equality is exact" in new TestContext {
        val customerBusinessNameRaw    = "Case Sensitive Name"
        val customerNameRaw            = "case sensitive name"
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          businessName = CustomerBusinessName.either(customerBusinessNameRaw).toOption.value,
          isArchived = false,
        )
        val customerName = CustomerName.either(customerNameRaw).toOption.value

        customerName.value shouldNot equal(customerBusinessDetailsRow.businessName.value)
        customerName.value.toLowerCase(java.util.Locale.ROOT) shouldBe customerBusinessDetailsRow.businessName.value
          .toLowerCase(java.util.Locale.ROOT)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        customerBookRepository
          .isUniqueCustomerName(
            customerBusinessDetailsRow.organizationID,
            customerName,
            CustomerType.Business,
            None,
          )
          .zioValue shouldBe true

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs Nil
      }
    }

    // uq_customer_name is a partial unique index on (organization_id, customer_type, name) WHERE is_archived = false,
    // so uniqueness is scoped to *active* customers *of the same kind*.
    "the uq_customer_name partial unique index" should {
      "allow an individual and a business to share a name within an organization" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val sharedName     = "Shared Name"

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            fullName = CustomerFullName.assume(sharedName),
            isArchived = false,
          )
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationID,
            businessName = CustomerBusinessName.assume(sharedName),
            isArchived = false,
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 2
      }

      "allow a new active customer to reuse an archived customer's name" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val reusedName     = CustomerFullName.assume("Reused Name")

        val customerIndividualDetailsRowArchived = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(organizationID = organizationID, fullName = reusedName, isArchived = true)
        val customerIndividualDetailsRowActive = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(organizationID = organizationID, fullName = reusedName, isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowArchived))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowActive))
          .zioValue

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 2
      }
    }
  }

  trait TestContext {
    val instantNow: Instant = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig: RepositoryConfig = RepositoryConfig(
      schema = "local_schema",
      customerTable = "customer",
      customerBusinessContactTable = "customer_business_contact",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val customerBookQueries = ZIO
      .service[CustomerBookQueries]
      .provide(CustomerBookQueries.live, ZLayer.succeed(repositoryConfig))
      .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val customerBookRepository = ZIO
      .service[CustomerBookRepository]
      .provide(
        CustomerBookRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(customerBookQueries),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
      )
      .zioValue
  }
}
