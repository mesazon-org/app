package io.mesazon.gateway.fun

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.repository.CustomerBookRepository
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.{AddressValidator, EmailValidator, PhoneNumberDomainValidator}
import io.mesazon.gateway.validation.service.CustomerBookRequestValidator
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import zio.*

class CustomerBookServiceSpec extends ZWordSpecBase, CustomerBookSmithyArbitraries, RepositoryArbitraries {

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val repositoryError =
    ServiceError.InternalServerError.RepositoryError("repository failure", new RuntimeException("boom"))

  "CustomerBookService" when {
    "customerBookIndividualPost" should {
      "successfully insert a customer individual" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerBookIndividualPostRequest =
          arbitrarySample[CustomerBookIndividualPostRequest]
        val insertCustomerIndividualInput =
          customerBookIndividualPostRequest.transformInto[InsertCustomerIndividualInput]
        val customerID = arbitrarySample[CustomerID]

        customerBookRepositoryMock.insertCustomerIndividual
          .expects(organizationID, insertCustomerIndividualInput)
          .returningZIO(customerID)
          .once()

        val customerBookService = buildCustomerBookService

        val customerBookIndividualPostResponse = customerBookService
          .customerBookIndividualPost(
            organizationID.value,
            customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
          )
          .zioValue

        customerBookIndividualPostResponse shouldBe smithy.CustomerBookIndividualPostResponse(
          customerID = customerID.value
        )
      }

      "fail with a ValidationError and never reach the repository when the full name is invalid" in new TestContext {
        val organizationID                          = arbitrarySample[OrganizationID]
        val customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
          .transformInto[smithy.CustomerBookIndividualPostRequest]
          .copy(fullName = "")

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPost(organizationID.value, customerBookIndividualPostRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("fullName", nonEmptyTrimmedError, List("")))
        )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerBookIndividualPostRequest =
          arbitrarySample[CustomerBookIndividualPostRequest]
        val insertCustomerIndividualInput =
          customerBookIndividualPostRequest.transformInto[InsertCustomerIndividualInput]

        customerBookRepositoryMock.insertCustomerIndividual
          .expects(organizationID, insertCustomerIndividualInput)
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPost(
            organizationID.value,
            customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "customerBookBusinessPost" should {
      "successfully insert a customer business" in new TestContext {
        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookBusinessPostRequest =
          arbitrarySample[CustomerBookBusinessPostRequest]
        val insertCustomerBusinessInput = customerBookBusinessPostRequest.transformInto[InsertCustomerBusinessInput]
        val customerID                  = arbitrarySample[CustomerID]

        customerBookRepositoryMock.insertCustomerBusiness
          .expects(organizationID, insertCustomerBusinessInput)
          .returningZIO(customerID)
          .once()

        val customerBookService = buildCustomerBookService

        val customerBookBusinessPostResponse = customerBookService
          .customerBookBusinessPost(
            organizationID.value,
            customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
          )
          .zioValue

        customerBookBusinessPostResponse shouldBe smithy.CustomerBookBusinessPostResponse(
          customerID = customerID.value
        )
      }

      "fail with a ValidationError and never reach the repository when the business name is invalid" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]
          .copy(businessName = "")

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookBusinessPost(organizationID.value, customerBookBusinessPostRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("businessName", nonEmptyTrimmedError, List("")))
        )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookBusinessPostRequest =
          arbitrarySample[CustomerBookBusinessPostRequest]

        customerBookRepositoryMock.insertCustomerBusiness
          .expects(organizationID, customerBookBusinessPostRequest.transformInto[InsertCustomerBusinessInput])
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookBusinessPost(
            organizationID.value,
            customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "customerBookBatchPost" should {
      "successfully insert individuals and businesses and return individual IDs before business IDs" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest]
          ),
          customerIndividuals = List(
            arbitrarySample[CustomerBookIndividualPostRequest]
          ),
        )
        val insertCustomerIndividualInputs =
          customerBookBatchPostRequest.customerIndividuals.transformInto[List[InsertCustomerIndividualInput]]
        val insertCustomerBusinessInputs =
          customerBookBatchPostRequest.customerBusinesses.transformInto[List[InsertCustomerBusinessInput]]
        val customerIDIndividual = arbitrarySample[CustomerID]
        val customerIDBusiness   = arbitrarySample[CustomerID]
        val customerIDs          = List(customerIDIndividual, customerIDBusiness)

        customerIDIndividual shouldNot equal(customerIDBusiness)

        customerBookRepositoryMock.insertCustomers
          .expects(organizationID, insertCustomerIndividualInputs, insertCustomerBusinessInputs)
          .returningZIO(customerIDs)
          .once()

        val customerBookBatchPostResponse = buildCustomerBookService
          .customerBookBatchPost(
            organizationID.value,
            customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
          )
          .zioValue

        customerBookBatchPostResponse shouldBe smithy.CustomerBookBatchPostResponse(
          customerIDs = List(
            customerIDIndividual.value,
            customerIDBusiness.value,
          )
        )
      }

      "fail with accumulated validation errors and never reach the repository" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val customerBookBatchPostRequestSmithy = smithy.CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest]
              .transformInto[smithy.CustomerBookBusinessPostRequest]
              .copy(businessName = "")
          ),
          customerIndividuals = List(
            arbitrarySample[CustomerBookIndividualPostRequest]
              .transformInto[smithy.CustomerBookIndividualPostRequest]
              .copy(fullName = "")
          ),
        )
        val businessNameError = InvalidFieldError("businessName", nonEmptyTrimmedError, List(""))
        val fullNameError     = InvalidFieldError("fullName", nonEmptyTrimmedError, List(""))

        buildCustomerBookService
          .customerBookBatchPost(organizationID.value, customerBookBatchPostRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError("customerBusiness", s"Failed with invalid fields [$businessNameError]", List()),
            InvalidFieldError("customerIndividual", s"Failed with invalid fields [$fullNameError]", List()),
          )
        )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest]
          ),
          customerIndividuals = List(
            arbitrarySample[CustomerBookIndividualPostRequest]
          ),
        )
        val insertCustomerIndividualInputs =
          customerBookBatchPostRequest.customerIndividuals.transformInto[List[InsertCustomerIndividualInput]]
        val insertCustomerBusinessInputs =
          customerBookBatchPostRequest.customerBusinesses.transformInto[List[InsertCustomerBusinessInput]]

        customerBookRepositoryMock.insertCustomers
          .expects(organizationID, insertCustomerIndividualInputs, insertCustomerBusinessInputs)
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCustomerBookService
          .customerBookBatchPost(
            organizationID.value,
            customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "customerBookIndividualPut" should {
      "successfully update a customer individual" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerID                        = arbitrarySample[CustomerID]
        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]
          .copy(
            emails = List(CustomerEmailEntry(arbitrarySample[CustomerEmail], isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntry(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          )
        val customerBookIndividualPutRequest = CustomerBookIndividualPutRequest(
          fullName = Some(customerBookIndividualPostRequest.fullName),
          emails = Some(customerBookIndividualPostRequest.emails),
          phoneNumbers = Some(customerBookIndividualPostRequest.phoneNumbers),
          addresses = Some(customerBookIndividualPostRequest.addresses),
        )
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]

        customerBookRepositoryMock.updateCustomerIndividual
          .expects(
            organizationID,
            customerID,
            customerBookIndividualPutRequest.fullName,
            customerBookIndividualPutRequest.emails.map(
              _.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              )
            ),
            customerBookIndividualPutRequest.phoneNumbers.map(
              _.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              )
            ),
            customerBookIndividualPutRequest.addresses,
          )
          .returningZIO(Some(customerIndividualDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPut(
            organizationID.value,
            customerID.value,
            customerBookIndividualPutRequest.transformInto[smithy.CustomerBookIndividualPutRequest],
          )
          .zioValue shouldBe ()
      }

      "successfully keep omitted lists unchanged" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerID                        = arbitrarySample[CustomerID]
        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]

        val customerBookIndividualPutRequest = CustomerBookIndividualPutRequest(
          fullName = Some(customerBookIndividualPostRequest.fullName),
          emails = None,
          phoneNumbers = None,
          addresses = None,
        )
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]

        customerBookRepositoryMock.updateCustomerIndividual
          .expects(
            organizationID,
            customerID,
            customerBookIndividualPutRequest.fullName,
            customerBookIndividualPutRequest.emails.map(
              _.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              )
            ),
            customerBookIndividualPutRequest.phoneNumbers.map(
              _.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              )
            ),
            customerBookIndividualPutRequest.addresses,
          )
          .returningZIO(Some(customerIndividualDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPut(
            organizationID.value,
            customerID.value,
            customerBookIndividualPutRequest.transformInto[smithy.CustomerBookIndividualPutRequest],
          )
          .zioValue shouldBe ()
      }

      "successfully clear explicitly empty lists" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerID                        = arbitrarySample[CustomerID]
        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]

        val customerBookIndividualPutRequest = CustomerBookIndividualPutRequest(
          fullName = Some(customerBookIndividualPostRequest.fullName),
          emails = Some(Nil),
          phoneNumbers = Some(Nil),
          addresses = Some(Nil),
        )
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]

        customerBookRepositoryMock.updateCustomerIndividual
          .expects(
            organizationID,
            customerID,
            customerBookIndividualPutRequest.fullName,
            customerBookIndividualPutRequest.emails.map(
              _.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              )
            ),
            customerBookIndividualPutRequest.phoneNumbers.map(
              _.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              )
            ),
            customerBookIndividualPutRequest.addresses,
          )
          .returningZIO(Some(customerIndividualDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPut(
            organizationID.value,
            customerID.value,
            customerBookIndividualPutRequest.transformInto[smithy.CustomerBookIndividualPutRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError and never reach the repository when the full name is invalid" in new TestContext {
        val organizationID                         = arbitrarySample[OrganizationID]
        val customerID                             = arbitrarySample[CustomerID]
        val customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(fullName = Some(""))

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPut(organizationID.value, customerID.value, customerBookIndividualPutRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("fullName", nonEmptyTrimmedError, List("")))
        )
      }

      "fail with an UnexpectedError and propagate the repository error unchanged" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val customerID      = arbitrarySample[CustomerID]
        val repositoryError = ServiceError.InternalServerError.UnexpectedError("update failed")

        customerBookRepositoryMock.updateCustomerIndividual
          .expects(organizationID, customerID, None, None, None, None)
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualPut(organizationID.value, customerID.value, smithy.CustomerBookIndividualPutRequest())
          .zioError shouldBe repositoryError
      }
    }

    "customerBookBusinessPut" should {
      "successfully map business replacements and all contact actions into one update" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val customerID                    = arbitrarySample[CustomerID]
        val customerBusinessContactInsert =
          arbitrarySample[CustomerBusinessContactInsert]
        val customerBusinessContactUpdate = CustomerBusinessContactUpdate(
          arbitrarySample[CustomerBusinessContactID],
          Some(arbitrarySample[CustomerFullName]),
          Some(Some(arbitrarySample[CustomerBusinessContactRole])),
          Some(None),
          None,
        )
        val customerBusinessContactArchive = CustomerBusinessContactArchive(arbitrarySample[CustomerBusinessContactID])
        val customerBookBusinessPutRequest = CustomerBookBusinessPutRequest(
          Some(arbitrarySample[CustomerBusinessName]),
          Some(List(CustomerEmailEntry(arbitrarySample[CustomerEmail], isDefault = true))),
          Some(Some(arbitrarySample[CustomerTaxID])),
          Some(List(CustomerPhoneNumberEntry(arbitrarySample[CustomerPhoneNumber], isDefault = true))),
          Some(List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling)))),
          List(customerBusinessContactInsert),
          List(customerBusinessContactUpdate),
          List(customerBusinessContactArchive),
        )
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]

        customerBusinessContactUpdate.customerBusinessContactID shouldNot equal(
          customerBusinessContactArchive.customerBusinessContactID
        )

        customerBookRepositoryMock.updateCustomerBusiness
          .expects(
            organizationID,
            customerID,
            customerBookBusinessPutRequest.businessName,
            customerBookBusinessPutRequest.emails.map(
              _.map(entry => CustomerEmailEntryInput(entry.email, isDefault = entry.isDefault))
            ),
            customerBookBusinessPutRequest.taxID,
            customerBookBusinessPutRequest.phoneNumbers.map(
              _.map(entry => CustomerPhoneNumberEntryInput(entry.phoneNumber, isDefault = entry.isDefault))
            ),
            customerBookBusinessPutRequest.addresses,
            List(
              UpdateCustomerBusinessContactInput(
                customerBusinessContactUpdate.customerBusinessContactID,
                customerBusinessContactUpdate.fullName,
                customerBusinessContactUpdate.role,
                customerBusinessContactUpdate.email,
                customerBusinessContactUpdate.phoneNumber,
              )
            ),
            List(
              CustomerBusinessContactInput(
                customerBusinessContactInsert.fullName,
                customerBusinessContactInsert.role,
                customerBusinessContactInsert.email,
                customerBusinessContactInsert.phoneNumber,
              )
            ),
            List(customerBusinessContactArchive.customerBusinessContactID),
          )
          .returningZIO(Some(customerBusinessDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService
        customerBookService
          .customerBookBusinessPut(
            organizationID.value,
            customerID.value,
            customerBookBusinessPutRequest.transformInto[smithy.CustomerBookBusinessPutRequest],
          )
          .zioValue shouldBe ()
      }

      "successfully preserve omitted fields and empty contact actions" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerID                 = arbitrarySample[CustomerID]
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]

        customerBookRepositoryMock.updateCustomerBusiness
          .expects(
            organizationID,
            customerID,
            None,
            None,
            None,
            None,
            None,
            Nil,
            Nil,
            Nil,
          )
          .returningZIO(Some(customerBusinessDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService
        customerBookService
          .customerBookBusinessPut(
            organizationID.value,
            customerID.value,
            smithy.CustomerBookBusinessPutRequest(),
          )
          .zioValue shouldBe ()
      }

      "successfully preserve explicit empty lists and nullable clears" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val customerID                     = arbitrarySample[CustomerID]
        val customerBusinessContactID      = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessDetailsRow     = arbitrarySample[CustomerBusinessDetailsRow]
        val customerBookBusinessPutRequest = CustomerBookBusinessPutRequest(
          None,
          Some(Nil),
          Some(None),
          Some(Nil),
          Some(Nil),
          Nil,
          List(CustomerBusinessContactUpdate(customerBusinessContactID, None, Some(None), Some(None), Some(None))),
          Nil,
        )

        customerBookRepositoryMock.updateCustomerBusiness
          .expects(
            organizationID,
            customerID,
            None,
            Some(Nil),
            Some(None),
            Some(Nil),
            Some(Nil),
            List(
              UpdateCustomerBusinessContactInput(customerBusinessContactID, None, Some(None), Some(None), Some(None))
            ),
            Nil,
            Nil,
          )
          .returningZIO(Some(customerBusinessDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService
        customerBookService
          .customerBookBusinessPut(
            organizationID.value,
            customerID.value,
            customerBookBusinessPutRequest.transformInto[smithy.CustomerBookBusinessPutRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError without reaching the repository when the business name is invalid" in new TestContext {
        val customerBookService = buildCustomerBookService
        customerBookService
          .customerBookBusinessPut(
            arbitrarySample[OrganizationID].value,
            arbitrarySample[CustomerID].value,
            smithy.CustomerBookBusinessPutRequest(businessName = Some("")),
          )
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(InvalidFieldError("businessName", nonEmptyTrimmedError, List("")))
        )
      }

      "fail with a RepositoryError and propagate it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.updateCustomerBusiness
          .expects(
            organizationID,
            customerID,
            None,
            None,
            None,
            None,
            None,
            Nil,
            Nil,
            Nil,
          )
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService
        customerBookService
          .customerBookBusinessPut(
            organizationID.value,
            customerID.value,
            smithy.CustomerBookBusinessPutRequest(),
          )
          .zioError shouldBe repositoryError
      }
    }

    "customerBookArchivePut" should {
      "successfully archive the customer identified by the path in the given organization" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.archiveCustomer
          .expects(organizationID, customerID)
          .returningZIO(Some(customerID))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookArchivePut(organizationID.value, customerID.value)
          .zioValue shouldBe ()
      }

      "fail with a RepositoryError and propagate it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.archiveCustomer
          .expects(organizationID, customerID)
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookArchivePut(organizationID.value, customerID.value)
          .zioError shouldBe repositoryError
      }
    }

    "customerBookIndividualGet" should {
      "successfully return the customer individual's full details" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]

        customerBookRepositoryMock.getCustomerIndividual
          .expects(organizationID, customerIndividualDetailsRow.customerID)
          .returningZIO(Some(customerIndividualDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService

        val customerBookIndividualGetResponse = customerBookService
          .customerBookIndividualGet(organizationID.value, customerIndividualDetailsRow.customerID.value)
          .zioValue

        customerBookIndividualGetResponse shouldBe smithy.CustomerBookIndividualGetResponse(
          customerID = customerIndividualDetailsRow.customerID.value,
          fullName = customerIndividualDetailsRow.fullName.value,
          emails = customerIndividualDetailsRow.emails.map(customerEmailEntryInput =>
            smithy.CustomerEmailEntry(
              email = customerEmailEntryInput.email.value,
              isDefault = customerEmailEntryInput.isDefault,
            )
          ),
          phoneNumbers = customerIndividualDetailsRow.phoneNumbers.map(customerPhoneNumberEntryInput =>
            smithy.CustomerPhoneNumberEntry(
              phoneNumber = smithy.PhoneNumberRequest(
                phoneNationalNumber = customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
                phoneCountryCode = customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
              ),
              isDefault = customerPhoneNumberEntryInput.isDefault,
            )
          ),
          addresses = customerIndividualDetailsRow.addresses.map(customerAddressEntry =>
            smithy.CustomerAddressEntry(
              address = smithy.AddressRequest(
                addressLine1 = customerAddressEntry.value.address.addressLine1.map(_.value),
                addressLine2 = customerAddressEntry.value.address.addressLine2.map(_.value),
                city = customerAddressEntry.value.address.city.map(_.value),
                postalCode = customerAddressEntry.value.address.postalCode.map(_.value),
                country = customerAddressEntry.value.address.country.map(_.value),
              ),
              addressType = customerAddressEntry.value.addressType.transformInto[smithy.AddressType],
            )
          ),
        )
      }

      "fail with an UnexpectedError when the customer individual does not exist" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.getCustomerIndividual
          .expects(organizationID, customerID)
          .returningZIO(None)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualGet(organizationID.value, customerID.value)
          .zioError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Customer individual not found for customerID: [${customerID.value}]"
        )
      }

      "fail with a RepositoryError and propagate it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.getCustomerIndividual
          .expects(organizationID, customerID)
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookIndividualGet(organizationID.value, customerID.value)
          .zioError shouldBe repositoryError
      }
    }

    "customerBookBusinessGet" should {
      "successfully return the customer business's full details" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBusinessDetailsRow =
          arbitrarySample[CustomerBusinessDetailsRow].copy(organizationID = organizationID)

        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessWithContactsRow = CustomerBusinessWithContactsRow(
          customerBusinessDetailsRow,
          List(customerBusinessContactRow),
        )

        customerBookRepositoryMock.getCustomerBusiness
          .expects(organizationID, customerBusinessDetailsRow.customerID)
          .returningZIO(Some(customerBusinessWithContactsRow))
          .once()

        val customerBookService = buildCustomerBookService

        val customerBookBusinessGetResponse = customerBookService
          .customerBookBusinessGet(organizationID.value, customerBusinessDetailsRow.customerID.value)
          .zioValue

        customerBookBusinessGetResponse shouldBe smithy.CustomerBookBusinessGetResponse(
          customerID = customerBusinessDetailsRow.customerID.value,
          businessName = customerBusinessDetailsRow.businessName.value,
          emails = customerBusinessDetailsRow.emails.map(customerEmailEntryInput =>
            smithy.CustomerEmailEntry(
              email = customerEmailEntryInput.email.value,
              isDefault = customerEmailEntryInput.isDefault,
            )
          ),
          taxID = customerBusinessDetailsRow.taxID.map(_.value),
          phoneNumbers = customerBusinessDetailsRow.phoneNumbers.map(customerPhoneNumberEntryInput =>
            smithy.CustomerPhoneNumberEntry(
              phoneNumber = smithy.PhoneNumberRequest(
                phoneNationalNumber = customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
                phoneCountryCode = customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
              ),
              isDefault = customerPhoneNumberEntryInput.isDefault,
            )
          ),
          addresses = customerBusinessDetailsRow.addresses.map(customerAddressEntry =>
            smithy.CustomerAddressEntry(
              address = smithy.AddressRequest(
                addressLine1 = customerAddressEntry.value.address.addressLine1.map(_.value),
                addressLine2 = customerAddressEntry.value.address.addressLine2.map(_.value),
                city = customerAddressEntry.value.address.city.map(_.value),
                postalCode = customerAddressEntry.value.address.postalCode.map(_.value),
                country = customerAddressEntry.value.address.country.map(_.value),
              ),
              addressType = customerAddressEntry.value.addressType.transformInto[smithy.AddressType],
            )
          ),
          customerBusinessContacts = List(
            smithy.CustomerBusinessContact(
              customerBusinessContactID = customerBusinessContactRow.customerBusinessContactID.value,
              fullName = customerBusinessContactRow.fullName.value,
              role = customerBusinessContactRow.role.map(_.value),
              email = customerBusinessContactRow.email.map(_.value),
              phoneNumber = customerBusinessContactRow.phoneNumber.map(customerPhoneNumber =>
                smithy.PhoneNumberRequest(
                  phoneNationalNumber = customerPhoneNumber.value.phoneNationalNumber.value,
                  phoneCountryCode = customerPhoneNumber.value.phoneCountryCode.value,
                )
              ),
            )
          ),
        )
      }

      "fail with an UnexpectedError when the customer business does not exist" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.getCustomerBusiness
          .expects(organizationID, customerID)
          .returningZIO(None)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookBusinessGet(organizationID.value, customerID.value)
          .zioError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Customer business not found for customerID: [${customerID.value}]"
        )
      }

      "fail with a RepositoryError and propagate it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerID     = arbitrarySample[CustomerID]

        customerBookRepositoryMock.getCustomerBusiness
          .expects(organizationID, customerID)
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookBusinessGet(organizationID.value, customerID.value)
          .zioError shouldBe repositoryError
      }
    }

    "customerBookSummaryGet" should {
      "successfully pass the ordered repeated IDs and return complete summaries in repository order" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val customerSummaryRowFirst  = arbitrarySample[CustomerSummaryRow]
        val customerSummaryRowSecond = arbitrarySample[CustomerSummaryRow]
        val customerIDs              = List(
          customerSummaryRowFirst.customerID,
          customerSummaryRowSecond.customerID,
          customerSummaryRowFirst.customerID,
        )

        customerSummaryRowFirst.customerID shouldNot equal(customerSummaryRowSecond.customerID)

        customerBookRepositoryMock.getCustomerSummaries
          .expects(organizationID, customerIDs)
          .returningZIO(List(customerSummaryRowSecond, customerSummaryRowFirst))
          .once()

        val customerBookService = buildCustomerBookService

        val customerBookSummaryGetResponse = customerBookService
          .customerBookSummaryGet(organizationID.value, customerIDs.map(_.value))
          .zioValue

        customerBookSummaryGetResponse shouldBe smithy.CustomerBookSummaryGetResponse(
          customers = List(
            smithy.CustomerSummary(
              customerID = customerSummaryRowSecond.customerID.value,
              name = customerSummaryRowSecond.name.value,
              customerType = customerSummaryRowSecond.customerType.transformInto[smithy.CustomerType],
            ),
            smithy.CustomerSummary(
              customerID = customerSummaryRowFirst.customerID.value,
              name = customerSummaryRowFirst.name.value,
              customerType = customerSummaryRowFirst.customerType.transformInto[smithy.CustomerType],
            ),
          )
        )
      }

      "fail with a RepositoryError and propagate it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerIDs    = List(arbitrarySample[CustomerID])

        customerBookRepositoryMock.getCustomerSummaries
          .expects(organizationID, customerIDs)
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookSummaryGet(organizationID.value, customerIDs.map(_.value))
          .zioError shouldBe repositoryError
      }
    }

    "customerBookUniquenessGet" should {
      "successfully pass the complete validated name check and return its result" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerName   = arbitrarySample[CustomerName]
        val customerType   = arbitrarySample[CustomerType]
        val customerIDOpt  = Some(arbitrarySample[CustomerID])
        val isNameUnique   = arbitrarySample[Boolean]

        customerBookRepositoryMock.isUniqueCustomerName
          .expects(organizationID, customerName, customerType, customerIDOpt)
          .returningZIO(isNameUnique)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookUniquenessGet(
            organizationID.value,
            Some(customerName.value),
            Some(customerType.transformInto[smithy.CustomerType]),
            customerIDOpt.map(_.value),
          )
          .zioValue shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique = isNameUnique)
      }

      "fail with a ValidationError before repository access when name and customer type are absent" in new TestContext {
        val organizationID      = arbitrarySample[OrganizationID]
        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookUniquenessGet(organizationID.value, None, None, None)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", "Name must be provided", Seq.empty),
            InvalidFieldError("customerType", "Customer type must be provided", Seq.empty),
          )
        )
      }

      "fail with a RepositoryError and propagate it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val customerName   = arbitrarySample[CustomerName]
        val customerType   = arbitrarySample[CustomerType]
        val customerIDOpt  = Option.empty[CustomerID]

        customerBookRepositoryMock.isUniqueCustomerName
          .expects(organizationID, customerName, customerType, customerIDOpt)
          .failingZIO(repositoryError)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .customerBookUniquenessGet(
            organizationID.value,
            Some(customerName.value),
            Some(customerType.transformInto[smithy.CustomerType]),
            customerIDOpt.map(_.value),
          )
          .zioError shouldBe repositoryError
      }
    }

  }

  trait TestContext {
    val customerBookRepositoryMock = mock[CustomerBookRepository]

    def buildCustomerBookService: smithy.CustomerBookService[ServiceTask] =
      ZIO
        .service[smithy.CustomerBookService[ServiceTask]]
        .provide(
          CustomerBookService.local,
          CustomerBookRequestValidator.live,
          EmailValidator.live,
          AddressValidator.live,
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
          ZLayer.succeed(customerBookRepositoryMock),
        )
        .zioValue
  }
}
