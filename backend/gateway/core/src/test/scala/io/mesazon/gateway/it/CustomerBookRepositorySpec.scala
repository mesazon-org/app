package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
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

class CustomerBookRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

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
      "insert a customer of type INDIVIDUAL and its details row in one transaction" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val customerID                    = arbitrarySample[CustomerID]
        val insertCustomerIndividualInput = arbitrarySample[InsertCustomerIndividualInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID.value).once(),
        )

        customerBookRepository.insertCustomerIndividual(organizationID, insertCustomerIndividualInput).zioValue shouldBe
          customerID

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(
            CustomerIndividualDetailsRow(
              organizationID,
              customerID,
              insertCustomerIndividualInput.fullName,
              insertCustomerIndividualInput.emails,
              insertCustomerIndividualInput.phoneNumbers,
              insertCustomerIndividualInput.addressLine1,
              insertCustomerIndividualInput.addressLine2,
              insertCustomerIndividualInput.city,
              insertCustomerIndividualInput.postalCode,
              insertCustomerIndividualInput.country,
              CustomerStatus.Active,
              CreatedAt(instantNow),
              UpdatedAt(instantNow),
            )
          )
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
        serviceError.message shouldBe "A customer with the given full name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
          List(customerID1)
      }
    }

    "insertCustomerIndividuals" should {
      "insert multiple individuals in a single transaction" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val customerID1                    = arbitrarySample[CustomerID]
        val customerID2                    = arbitrarySample[CustomerID]
        val insertCustomerIndividualInput1 =
          arbitrarySample[InsertCustomerIndividualInput].copy(fullName = CustomerFullName.assume("Individual One"))
        val insertCustomerIndividualInput2 =
          arbitrarySample[InsertCustomerIndividualInput].copy(fullName = CustomerFullName.assume("Individual Two"))

        customerID1 shouldNot equal(customerID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerID2.value).once(),
        )

        customerBookRepository
          .insertCustomerIndividuals(
            organizationID,
            List(insertCustomerIndividualInput1, insertCustomerIndividualInput2),
          )
          .zioValue shouldBe List(customerID1, customerID2)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue should
          contain theSameElementsAs List(
            CustomerIndividualDetailsRow(
              organizationID,
              customerID1,
              insertCustomerIndividualInput1.fullName,
              insertCustomerIndividualInput1.emails,
              insertCustomerIndividualInput1.phoneNumbers,
              insertCustomerIndividualInput1.addressLine1,
              insertCustomerIndividualInput1.addressLine2,
              insertCustomerIndividualInput1.city,
              insertCustomerIndividualInput1.postalCode,
              insertCustomerIndividualInput1.country,
              CustomerStatus.Active,
              CreatedAt(instantNow),
              UpdatedAt(instantNow),
            ),
            CustomerIndividualDetailsRow(
              organizationID,
              customerID2,
              insertCustomerIndividualInput2.fullName,
              insertCustomerIndividualInput2.emails,
              insertCustomerIndividualInput2.phoneNumbers,
              insertCustomerIndividualInput2.addressLine1,
              insertCustomerIndividualInput2.addressLine2,
              insertCustomerIndividualInput2.city,
              insertCustomerIndividualInput2.postalCode,
              insertCustomerIndividualInput2.country,
              CustomerStatus.Active,
              CreatedAt(instantNow),
              UpdatedAt(instantNow),
            ),
          )
      }
    }

    "insertCustomerBusiness" should {
      "insert a customer of type BUSINESS, its details, and any inline contacts in one transaction" in new TestContext {
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

        customerBookRepository.insertCustomerBusiness(organizationID, insertCustomerBusinessInput).zioValue shouldBe
          customerID

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(
          CustomerBusinessDetailsRow(
            organizationID,
            customerID,
            insertCustomerBusinessInput.businessName,
            insertCustomerBusinessInput.taxID,
            insertCustomerBusinessInput.emails,
            insertCustomerBusinessInput.phoneNumbers,
            insertCustomerBusinessInput.addressLine1,
            insertCustomerBusinessInput.addressLine2,
            insertCustomerBusinessInput.city,
            insertCustomerBusinessInput.postalCode,
            insertCustomerBusinessInput.country,
            CustomerStatus.Active,
            CreatedAt(instantNow),
            UpdatedAt(instantNow),
          )
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(
          CustomerBusinessContactRow(
            organizationID,
            customerID,
            customerBusinessContactID,
            customerBusinessContactInput.fullName,
            customerBusinessContactInput.role,
            customerBusinessContactInput.email,
            customerBusinessContactInput.phoneNumber,
            CreatedAt(instantNow),
            UpdatedAt(instantNow),
          )
        )
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
        serviceError.message shouldBe "A customer with the given business name already exists in this organization"
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

        customerBookRepository
          .insertCustomers(organizationID, List(insertCustomerIndividualInput), List(insertCustomerBusinessInput))
          .zioValue shouldBe List(customerIDIndividual, customerIDBusiness)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(
          CustomerIndividualDetailsRow(
            organizationID,
            customerIDIndividual,
            insertCustomerIndividualInput.fullName,
            insertCustomerIndividualInput.emails,
            insertCustomerIndividualInput.phoneNumbers,
            insertCustomerIndividualInput.addressLine1,
            insertCustomerIndividualInput.addressLine2,
            insertCustomerIndividualInput.city,
            insertCustomerIndividualInput.postalCode,
            insertCustomerIndividualInput.country,
            CustomerStatus.Active,
            CreatedAt(instantNow),
            UpdatedAt(instantNow),
          )
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(
          CustomerBusinessDetailsRow(
            organizationID,
            customerIDBusiness,
            insertCustomerBusinessInput.businessName,
            insertCustomerBusinessInput.taxID,
            insertCustomerBusinessInput.emails,
            insertCustomerBusinessInput.phoneNumbers,
            insertCustomerBusinessInput.addressLine1,
            insertCustomerBusinessInput.addressLine2,
            insertCustomerBusinessInput.city,
            insertCustomerBusinessInput.postalCode,
            insertCustomerBusinessInput.country,
            CustomerStatus.Active,
            CreatedAt(instantNow),
            UpdatedAt(instantNow),
          )
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe empty
      }
    }

    "updateCustomerIndividual" should {
      "update the details row in place and return the updated row" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerFullNameUpdate         = arbitrarySample[CustomerFullName]
        val customerEmailEntryInputsUpdate = arbitrarySample[List[CustomerEmailEntryInput]]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val customerIndividualDetailsRowUpdated = customerBookRepository
          .updateCustomerIndividual(
            customerIndividualDetailsRow.organizationID,
            customerIndividualDetailsRow.customerID,
            fullNameOptUpdate = Some(customerFullNameUpdate),
            emailsOptUpdate = Some(customerEmailEntryInputsUpdate),
          )
          .zioValue

        customerIndividualDetailsRowUpdated shouldBe customerIndividualDetailsRow.copy(
          fullName = customerFullNameUpdate,
          emails = customerEmailEntryInputsUpdate,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRowUpdated)
      }
    }

    "updateCustomerBusiness" should {
      "update the details row in place and return the updated row" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val customerBusinessNameUpdate = arbitrarySample[CustomerBusinessName]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val customerBusinessDetailsRowUpdated = customerBookRepository
          .updateCustomerBusiness(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            businessNameOptUpdate = Some(customerBusinessNameUpdate),
          )
          .zioValue

        customerBusinessDetailsRowUpdated shouldBe
          customerBusinessDetailsRow.copy(businessName = customerBusinessNameUpdate, updatedAt = UpdatedAt(instantNow))

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe
          List(customerBusinessDetailsRowUpdated)
      }
    }

    "addCustomerBusinessContacts" should {
      "insert the contacts under the business and return the created rows" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val customerBusinessContactID    = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactInput = arbitrarySample[CustomerBusinessContactInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID.value).once(),
        )

        val customerBusinessContactRowsAdded = customerBookRepository
          .addCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List(customerBusinessContactInput),
          )
          .zioValue

        customerBusinessContactRowsAdded shouldBe List(
          CustomerBusinessContactRow(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            customerBusinessContactID,
            customerBusinessContactInput.fullName,
            customerBusinessContactInput.role,
            customerBusinessContactInput.email,
            customerBusinessContactInput.phoneNumber,
            CreatedAt(instantNow),
            UpdatedAt(instantNow),
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe
          customerBusinessContactRowsAdded
      }

      "fail with a UniqueConstraintViolation when the email already exists for the customer, rolling back the second contact" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val customerEmail        = arbitrarySample[CustomerEmail]
        val customerPhoneNumber1 = arbitrarySample[CustomerPhoneNumber]
        val phoneNumber2         = arbitrarySample[CustomerPhoneNumber].value
        val customerPhoneNumber2 = CustomerPhoneNumber.assume(
          phoneNumber2.copy(
            phoneNationalNumber = PhoneNationalNumber.assume(s"${phoneNumber2.phoneNationalNumber.value}1"),
            phoneNumberE164 = PhoneNumberE164.assume(s"${phoneNumber2.phoneNumberE164.value}1"),
          )
        )
        val customerBusinessContactID1 = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactID2 = arbitrarySample[CustomerBusinessContactID]

        customerPhoneNumber1 shouldNot equal(customerPhoneNumber2)

        val customerBusinessContactInput1 = arbitrarySample[CustomerBusinessContactInput]
          .copy(email = Some(customerEmail), phoneNumber = Some(customerPhoneNumber1))
        val customerBusinessContactInput2 = arbitrarySample[CustomerBusinessContactInput]
          .copy(email = Some(customerEmail), phoneNumber = Some(customerPhoneNumber2))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID2.value).once(),
        )

        customerBookRepository
          .addCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List(customerBusinessContactInput1),
          )
          .zioValue

        val serviceError = customerBookRepository
          .addCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List(customerBusinessContactInput2),
          )
          .zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A business contact with the given email already exists for this customer"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue
          .map(_.customerBusinessContactID) shouldBe List(customerBusinessContactID1)
      }

      "fail with a UniqueConstraintViolation when the phone number already exists for the customer, rolling back the second contact" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val customerPhoneNumber        = arbitrarySample[CustomerPhoneNumber]
        val customerEmail1             = arbitrarySample[CustomerEmail]
        val customerEmail2             = CustomerEmail.assume(s"x${arbitrarySample[CustomerEmail].value}")
        val customerBusinessContactID1 = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactID2 = arbitrarySample[CustomerBusinessContactID]

        customerEmail1 shouldNot equal(customerEmail2)

        val customerBusinessContactInput1 = arbitrarySample[CustomerBusinessContactInput]
          .copy(email = Some(customerEmail1), phoneNumber = Some(customerPhoneNumber))
        val customerBusinessContactInput2 = arbitrarySample[CustomerBusinessContactInput]
          .copy(email = Some(customerEmail2), phoneNumber = Some(customerPhoneNumber))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerBusinessContactID2.value).once(),
        )

        customerBookRepository
          .addCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List(customerBusinessContactInput1),
          )
          .zioValue

        val serviceError = customerBookRepository
          .addCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List(customerBusinessContactInput2),
          )
          .zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A business contact with the given phone number already exists for this customer"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue
          .map(_.customerBusinessContactID) shouldBe List(customerBusinessContactID1)
      }
    }

    "removeCustomerBusinessContacts" should {
      "hard-delete only the contacts whose ids are given, leaving the rest" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)

        val customerEmail1       = arbitrarySample[CustomerEmail]
        val customerEmail2       = CustomerEmail.assume(s"x${customerEmail1.value}")
        val customerPhoneNumber1 = arbitrarySample[CustomerPhoneNumber]
        val phoneNumber1         = customerPhoneNumber1.value
        val customerPhoneNumber2 = CustomerPhoneNumber.assume(
          phoneNumber1.copy(
            phoneNationalNumber = PhoneNationalNumber.assume(s"${phoneNumber1.phoneNationalNumber.value}1"),
            phoneNumberE164 = PhoneNumberE164.assume(s"${phoneNumber1.phoneNumberE164.value}1"),
          )
        )

        customerEmail1 shouldNot equal(customerEmail2)
        customerPhoneNumber1 shouldNot equal(customerPhoneNumber2)

        val customerBusinessContactRow1 = arbitrarySample[CustomerBusinessContactRow]
          .copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            email = Some(customerEmail1),
            phoneNumber = Some(customerPhoneNumber1),
          )
        val customerBusinessContactRow2 = arbitrarySample[CustomerBusinessContactRow]
          .copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            email = Some(customerEmail2),
            phoneNumber = Some(customerPhoneNumber2),
          )

        customerBusinessContactRow1.customerBusinessContactID shouldNot
          equal(customerBusinessContactRow2.customerBusinessContactID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(
              List(customerBusinessContactRow1, customerBusinessContactRow2)
            )
          )
          .zioValue

        customerBookRepository
          .removeCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List(customerBusinessContactRow1.customerBusinessContactID),
          )
          .zioValue

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe
          List(customerBusinessContactRow2)
      }

      "no-op when the contact id list is empty" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow]
          .copy(
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
          .removeCustomerBusinessContacts(
            customerBusinessDetailsRow.organizationID,
            customerBusinessDetailsRow.customerID,
            List.empty,
          )
          .zioValue

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe
          List(customerBusinessContactRow)
      }
    }

    "getCustomerIndividual" should {
      "return the details row when it exists" in new TestContext {
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        customerBookRepository
          .getCustomerIndividual(customerIndividualDetailsRow.organizationID, customerIndividualDetailsRow.customerID)
          .zioValue shouldBe Some(customerIndividualDetailsRow)
      }

      "return None when it does not exist" in new TestContext {
        customerBookRepository
          .getCustomerIndividual(arbitrarySample[OrganizationID], arbitrarySample[CustomerID])
          .zioValue shouldBe None
      }
    }

    "getCustomerBusiness" should {
      "return the details row when it exists" in new TestContext {
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(status = CustomerStatus.Active)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        customerBookRepository
          .getCustomerBusiness(customerBusinessDetailsRow.organizationID, customerBusinessDetailsRow.customerID)
          .zioValue shouldBe Some(customerBusinessDetailsRow)
      }

      "return None when it does not exist" in new TestContext {
        customerBookRepository
          .getCustomerBusiness(arbitrarySample[OrganizationID], arbitrarySample[CustomerID])
          .zioValue shouldBe None
      }
    }

    "getCustomers" should {
      "return a summary of every ACTIVE customer sorted case-insensitively by display name" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        val customerIndividualDetailsRow1 = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            status = CustomerStatus.Active,
            fullName = CustomerFullName.assume("alpha individual"),
          )
        val customerIndividualDetailsRow2 = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationID,
            status = CustomerStatus.Active,
            fullName = CustomerFullName.assume("Zeta Individual"),
          )
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationID,
            status = CustomerStatus.Active,
            businessName = CustomerBusinessName.assume("Beta Business"),
          )

        customerIndividualDetailsRow1.customerID shouldNot equal(customerIndividualDetailsRow2.customerID)
        customerIndividualDetailsRow1.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
        customerIndividualDetailsRow2.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow2))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow1))
          .zioValue

        customerBookRepository.getCustomers(organizationID).zioValue shouldBe List(
          CustomerSummaryRow(
            customerIndividualDetailsRow1.customerID,
            CustomerName.assume(customerIndividualDetailsRow1.fullName.value),
            CustomerType.Individual,
          ),
          CustomerSummaryRow(
            customerBusinessDetailsRow.customerID,
            CustomerName.assume(customerBusinessDetailsRow.businessName.value),
            CustomerType.Business,
          ),
          CustomerSummaryRow(
            customerIndividualDetailsRow2.customerID,
            CustomerName.assume(customerIndividualDetailsRow2.fullName.value),
            CustomerType.Individual,
          ),
        )
      }
    }

    "the individual/business type-exclusivity invariant" should {
      "never place a customer_id in both detail tables" in new TestContext {
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
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(customerIDBusiness.value).once(),
        )

        customerBookRepository.insertCustomerIndividual(organizationID, insertCustomerIndividualInput).zioValue
        customerBookRepository.insertCustomerBusiness(organizationID, insertCustomerBusinessInput).zioValue

        val customerIDsIndividual =
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue
            .map(_.customerID)
            .toSet
        val customerIDsBusiness =
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue
            .map(_.customerID)
            .toSet

        customerIDsIndividual should contain(customerIDIndividual)
        customerIDsBusiness should contain(customerIDBusiness)
        (customerIDsIndividual intersect customerIDsBusiness) shouldBe empty
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
