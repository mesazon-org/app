package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.repository.CustomerBookRepository
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.{EmailValidator, PhoneNumberDomainValidator}
import io.mesazon.gateway.validation.service.CustomerBookRequestValidator
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import zio.*

class CustomerBookServiceSpec extends ZWordSpecBase, CustomerBookSmithyArbitraries, RepositoryArbitraries {

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private def toInsertCustomerIndividualInput(
      request: InsertCustomerIndividualPostRequest
  ): InsertCustomerIndividualInput =
    InsertCustomerIndividualInput(
      fullName = request.fullName,
      emails = request.emails.map(entry => CustomerEmailEntryInput(email = entry.email, isDefault = entry.isDefault)),
      phoneNumbers = request.phoneNumbers.map(entry =>
        CustomerPhoneNumberEntryInput(phoneNumber = entry.phoneNumber, isDefault = entry.isDefault)
      ),
      addressLine1 = request.addressLine1,
      addressLine2 = request.addressLine2,
      city = request.city,
      postalCode = request.postalCode,
      country = request.country,
    )

  private def toInsertCustomerBusinessInput(request: InsertCustomerBusinessPostRequest): InsertCustomerBusinessInput =
    InsertCustomerBusinessInput(
      businessName = request.businessName,
      emails = request.emails.map(entry => CustomerEmailEntryInput(email = entry.email, isDefault = entry.isDefault)),
      taxID = request.taxID,
      phoneNumbers = request.phoneNumbers.map(entry =>
        CustomerPhoneNumberEntryInput(phoneNumber = entry.phoneNumber, isDefault = entry.isDefault)
      ),
      addressLine1 = request.addressLine1,
      addressLine2 = request.addressLine2,
      city = request.city,
      postalCode = request.postalCode,
      country = request.country,
      customerBusinessContacts = request.customerBusinessContacts.map(contact =>
        CustomerBusinessContactInput(
          fullName = contact.fullName,
          role = contact.role,
          email = contact.email,
          phoneNumber = contact.phoneNumber,
        )
      ),
    )

  private val repositoryError =
    ServiceError.InternalServerError.RepositoryError("repository failure", new RuntimeException("boom"))

  "CustomerBookService" when {
    "insertCustomerIndividualPost" should {
      "successfully insert a customer individual" in new TestContext {
        val organizationID                      = arbitrarySample[OrganizationID]
        val customerID                          = arbitrarySample[CustomerID]
        val insertCustomerIndividualPostRequest = arbitrarySample[InsertCustomerIndividualPostRequest]

        customerBookRepositoryMock.insertCustomerIndividual
          .expects(organizationID, toInsertCustomerIndividualInput(insertCustomerIndividualPostRequest))
          .returningZIO(customerID)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerIndividualPost(
            organizationID.value,
            insertCustomerIndividualPostRequest.transformInto[smithy.InsertCustomerIndividualPostRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError and never reach the repository when the full name is invalid" in new TestContext {
        val organizationID                            = arbitrarySample[OrganizationID]
        val insertCustomerIndividualPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = "")

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerIndividualPost(organizationID.value, insertCustomerIndividualPostRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("fullName", nonEmptyTrimmedError, List("")))
        )
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID                      = arbitrarySample[OrganizationID]
        val insertCustomerIndividualPostRequest = arbitrarySample[InsertCustomerIndividualPostRequest]

        customerBookRepositoryMock.insertCustomerIndividual
          .expects(organizationID, toInsertCustomerIndividualInput(insertCustomerIndividualPostRequest))
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerIndividualPost(
            organizationID.value,
            insertCustomerIndividualPostRequest.transformInto[smithy.InsertCustomerIndividualPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "insertCustomerIndividualsPost" should {
      "successfully insert a batch of customer individuals" in new TestContext {
        val organizationID                       = arbitrarySample[OrganizationID]
        val insertCustomerIndividualsPostRequest = arbitrarySample[InsertCustomerIndividualsPostRequest]
        val insertCustomerIndividualInputs       =
          insertCustomerIndividualsPostRequest.customerIndividuals.map(toInsertCustomerIndividualInput)
        val customerIDs = insertCustomerIndividualInputs.map(_ => arbitrarySample[CustomerID])

        customerBookRepositoryMock.insertCustomerIndividuals
          .expects(organizationID, insertCustomerIndividualInputs)
          .returningZIO(customerIDs)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerIndividualsPost(
            organizationID.value,
            insertCustomerIndividualsPostRequest.transformInto[smithy.InsertCustomerIndividualsPostRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID                       = arbitrarySample[OrganizationID]
        val insertCustomerIndividualsPostRequest = arbitrarySample[InsertCustomerIndividualsPostRequest]
        val insertCustomerIndividualInputs       =
          insertCustomerIndividualsPostRequest.customerIndividuals.map(toInsertCustomerIndividualInput)

        customerBookRepositoryMock.insertCustomerIndividuals
          .expects(organizationID, insertCustomerIndividualInputs)
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerIndividualsPost(
            organizationID.value,
            insertCustomerIndividualsPostRequest.transformInto[smithy.InsertCustomerIndividualsPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "insertCustomerBusinessPost" should {
      "successfully insert a customer business" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerID                        = arbitrarySample[CustomerID]
        val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]

        customerBookRepositoryMock.insertCustomerBusiness
          .expects(organizationID, toInsertCustomerBusinessInput(insertCustomerBusinessPostRequest))
          .returningZIO(customerID)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerBusinessPost(
            organizationID.value,
            insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError and never reach the repository when the business name is invalid" in new TestContext {
        val organizationID                          = arbitrarySample[OrganizationID]
        val insertCustomerBusinessPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerBusinessPostRequest].copy(businessName = "")

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerBusinessPost(organizationID.value, insertCustomerBusinessPostRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("businessName", nonEmptyTrimmedError, List("")))
        )
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]

        customerBookRepositoryMock.insertCustomerBusiness
          .expects(organizationID, toInsertCustomerBusinessInput(insertCustomerBusinessPostRequest))
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerBusinessPost(
            organizationID.value,
            insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "insertCustomerBusinessesPost" should {
      "successfully insert a batch of customer businesses" in new TestContext {
        val organizationID                      = arbitrarySample[OrganizationID]
        val insertCustomerBusinessesPostRequest = arbitrarySample[InsertCustomerBusinessesPostRequest]
        val insertCustomerBusinessInputs        =
          insertCustomerBusinessesPostRequest.customerBusinesses.map(toInsertCustomerBusinessInput)
        val customerIDs = insertCustomerBusinessInputs.map(_ => arbitrarySample[CustomerID])

        customerBookRepositoryMock.insertCustomerBusinesses
          .expects(organizationID, insertCustomerBusinessInputs)
          .returningZIO(customerIDs)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerBusinessesPost(
            organizationID.value,
            insertCustomerBusinessesPostRequest.transformInto[smithy.InsertCustomerBusinessesPostRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID                      = arbitrarySample[OrganizationID]
        val insertCustomerBusinessesPostRequest = arbitrarySample[InsertCustomerBusinessesPostRequest]
        val insertCustomerBusinessInputs        =
          insertCustomerBusinessesPostRequest.customerBusinesses.map(toInsertCustomerBusinessInput)

        customerBookRepositoryMock.insertCustomerBusinesses
          .expects(organizationID, insertCustomerBusinessInputs)
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomerBusinessesPost(
            organizationID.value,
            insertCustomerBusinessesPostRequest.transformInto[smithy.InsertCustomerBusinessesPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "insertCustomersPost" should {
      "successfully insert individuals and businesses in one payload" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val insertCustomersPostRequest     = arbitrarySample[InsertCustomersPostRequest]
        val insertCustomerIndividualInputs =
          insertCustomersPostRequest.customerIndividuals.map(toInsertCustomerIndividualInput)
        val insertCustomerBusinessInputs =
          insertCustomersPostRequest.customerBusinesses.map(toInsertCustomerBusinessInput)
        val customerIDs =
          (insertCustomerIndividualInputs ++ insertCustomerBusinessInputs).map(_ => arbitrarySample[CustomerID])

        customerBookRepositoryMock.insertCustomers
          .expects(organizationID, insertCustomerIndividualInputs, insertCustomerBusinessInputs)
          .returningZIO(customerIDs)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomersPost(
            organizationID.value,
            insertCustomersPostRequest.transformInto[smithy.InsertCustomersPostRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val insertCustomersPostRequest     = arbitrarySample[InsertCustomersPostRequest]
        val insertCustomerIndividualInputs =
          insertCustomersPostRequest.customerIndividuals.map(toInsertCustomerIndividualInput)
        val insertCustomerBusinessInputs =
          insertCustomersPostRequest.customerBusinesses.map(toInsertCustomerBusinessInput)

        customerBookRepositoryMock.insertCustomers
          .expects(organizationID, insertCustomerIndividualInputs, insertCustomerBusinessInputs)
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .insertCustomersPost(
            organizationID.value,
            insertCustomersPostRequest.transformInto[smithy.InsertCustomersPostRequest],
          )
          .zioError shouldBe repositoryError
      }
    }

    "updateCustomerIndividualPut" should {
      "successfully update a customer individual" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val updateCustomerIndividualPutRequest = arbitrarySample[UpdateCustomerIndividualPutRequest]
        val customerIndividualDetailsRow       = arbitrarySample[CustomerIndividualDetailsRow]

        customerBookRepositoryMock.updateCustomerIndividual
          .expects(
            organizationID,
            updateCustomerIndividualPutRequest.customerID,
            updateCustomerIndividualPutRequest.fullName,
            Some(
              updateCustomerIndividualPutRequest.emails.map(entry =>
                CustomerEmailEntryInput(email = entry.email, isDefault = entry.isDefault)
              )
            ),
            Some(
              updateCustomerIndividualPutRequest.phoneNumbers.map(entry =>
                CustomerPhoneNumberEntryInput(phoneNumber = entry.phoneNumber, isDefault = entry.isDefault)
              )
            ),
            updateCustomerIndividualPutRequest.addressLine1,
            updateCustomerIndividualPutRequest.addressLine2,
            updateCustomerIndividualPutRequest.city,
            updateCustomerIndividualPutRequest.postalCode,
            updateCustomerIndividualPutRequest.country,
          )
          .returningZIO(customerIndividualDetailsRow)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .updateCustomerIndividualPut(
            organizationID.value,
            updateCustomerIndividualPutRequest.transformInto[smithy.UpdateCustomerIndividualPutRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError and never reach the repository when the full name is invalid" in new TestContext {
        val organizationID                           = arbitrarySample[OrganizationID]
        val updateCustomerIndividualPutRequestSmithy =
          arbitrarySample[smithy.UpdateCustomerIndividualPutRequest].copy(fullName = Some(""))

        val customerBookService = buildCustomerBookService

        customerBookService
          .updateCustomerIndividualPut(organizationID.value, updateCustomerIndividualPutRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("fullName", nonEmptyTrimmedError, List("")))
        )
      }
    }

    "updateCustomerBusinessPut" should {
      "successfully update a customer business" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val updateCustomerBusinessPutRequest = arbitrarySample[UpdateCustomerBusinessPutRequest]
        val customerBusinessDetailsRow       = arbitrarySample[CustomerBusinessDetailsRow]

        customerBookRepositoryMock.updateCustomerBusiness
          .expects(
            organizationID,
            updateCustomerBusinessPutRequest.customerID,
            updateCustomerBusinessPutRequest.businessName,
            Some(
              updateCustomerBusinessPutRequest.emails.map(entry =>
                CustomerEmailEntryInput(email = entry.email, isDefault = entry.isDefault)
              )
            ),
            updateCustomerBusinessPutRequest.taxID,
            Some(
              updateCustomerBusinessPutRequest.phoneNumbers.map(entry =>
                CustomerPhoneNumberEntryInput(phoneNumber = entry.phoneNumber, isDefault = entry.isDefault)
              )
            ),
            updateCustomerBusinessPutRequest.addressLine1,
            updateCustomerBusinessPutRequest.addressLine2,
            updateCustomerBusinessPutRequest.city,
            updateCustomerBusinessPutRequest.postalCode,
            updateCustomerBusinessPutRequest.country,
          )
          .returningZIO(customerBusinessDetailsRow)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .updateCustomerBusinessPut(
            organizationID.value,
            updateCustomerBusinessPutRequest.transformInto[smithy.UpdateCustomerBusinessPutRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError and never reach the repository when the business name is invalid" in new TestContext {
        val organizationID                         = arbitrarySample[OrganizationID]
        val updateCustomerBusinessPutRequestSmithy =
          arbitrarySample[smithy.UpdateCustomerBusinessPutRequest].copy(businessName = Some(""))

        val customerBookService = buildCustomerBookService

        customerBookService
          .updateCustomerBusinessPut(organizationID.value, updateCustomerBusinessPutRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("businessName", nonEmptyTrimmedError, List("")))
        )
      }
    }

    "addCustomerBusinessContactsPut" should {
      "successfully add contacts to a customer business" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val addCustomerBusinessContactsPutRequest = arbitrarySample[AddCustomerBusinessContactsPutRequest]
        val customerBusinessContactInputs         =
          addCustomerBusinessContactsPutRequest.customerBusinessContacts.map(contact =>
            CustomerBusinessContactInput(
              fullName = contact.fullName,
              role = contact.role,
              email = contact.email,
              phoneNumber = contact.phoneNumber,
            )
          )
        val customerBusinessContactRows =
          customerBusinessContactInputs.map(_ => arbitrarySample[CustomerBusinessContactRow])

        customerBookRepositoryMock.addCustomerBusinessContacts
          .expects(organizationID, addCustomerBusinessContactsPutRequest.customerID, customerBusinessContactInputs)
          .returningZIO(customerBusinessContactRows)
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .addCustomerBusinessContactsPut(
            organizationID.value,
            addCustomerBusinessContactsPutRequest.transformInto[smithy.AddCustomerBusinessContactsPutRequest],
          )
          .zioValue shouldBe ()
      }

      "fail with a ValidationError and never reach the repository when a contact's full name is invalid" in new TestContext {
        val organizationID                              = arbitrarySample[OrganizationID]
        val addCustomerBusinessContactsPutRequestSmithy =
          arbitrarySample[smithy.AddCustomerBusinessContactsPutRequest].copy(
            customerBusinessContacts = List(arbitrarySample[smithy.AddCustomerBusinessContact].copy(fullName = ""))
          )

        val customerBookService = buildCustomerBookService

        customerBookService
          .addCustomerBusinessContactsPut(organizationID.value, addCustomerBusinessContactsPutRequestSmithy)
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          invalidFields = List(InvalidFieldError("fullName", nonEmptyTrimmedError, List(""), index = 0))
        )
      }
    }

    "removeCustomerBusinessContactsPut" should {
      "successfully remove contacts from a customer business" in new TestContext {
        val organizationID                                 = arbitrarySample[OrganizationID]
        val removeCustomerBusinessContactsPutRequestSmithy =
          arbitrarySample[smithy.RemoveCustomerBusinessContactsPutRequest]

        customerBookRepositoryMock.removeCustomerBusinessContacts
          .expects(
            organizationID,
            CustomerID(removeCustomerBusinessContactsPutRequestSmithy.customerID),
            removeCustomerBusinessContactsPutRequestSmithy.customerBusinessContacts.map(customerBusinessContact =>
              CustomerBusinessContactID(customerBusinessContact.customerBusinessContactID)
            ),
          )
          .returningZIOUnit
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .removeCustomerBusinessContactsPut(organizationID.value, removeCustomerBusinessContactsPutRequestSmithy)
          .zioValue shouldBe ()
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID                                 = arbitrarySample[OrganizationID]
        val removeCustomerBusinessContactsPutRequestSmithy =
          arbitrarySample[smithy.RemoveCustomerBusinessContactsPutRequest]

        customerBookRepositoryMock.removeCustomerBusinessContacts
          .expects(
            organizationID,
            CustomerID(removeCustomerBusinessContactsPutRequestSmithy.customerID),
            removeCustomerBusinessContactsPutRequestSmithy.customerBusinessContacts.map(customerBusinessContact =>
              CustomerBusinessContactID(customerBusinessContact.customerBusinessContactID)
            ),
          )
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService
          .removeCustomerBusinessContactsPut(organizationID.value, removeCustomerBusinessContactsPutRequestSmithy)
          .zioError shouldBe repositoryError
      }
    }

    "getCustomerIndividualGet" should {
      "successfully return the customer individual's full details" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]

        customerBookRepositoryMock.getCustomerIndividual
          .expects(organizationID, customerIndividualDetailsRow.customerID)
          .returningZIO(Some(customerIndividualDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService

        val getCustomerIndividualGetResponse = customerBookService
          .getCustomerIndividualGet(organizationID.value, customerIndividualDetailsRow.customerID.value)
          .zioValue

        getCustomerIndividualGetResponse shouldBe smithy.GetCustomerIndividualGetResponse(
          customerID = customerIndividualDetailsRow.customerID.value,
          fullName = customerIndividualDetailsRow.fullName.value,
          emails = customerIndividualDetailsRow.emails.map(entry =>
            smithy.CustomerEmailEntryRequest(email = entry.email.value, isDefault = entry.isDefault)
          ),
          phoneNumbers = customerIndividualDetailsRow.phoneNumbers.map(entry =>
            smithy.CustomerPhoneNumberEntryRequest(
              phoneNumber = smithy.PhoneNumberRequest(
                phoneNationalNumber = entry.phoneNumber.value.phoneNationalNumber.value,
                phoneCountryCode = entry.phoneNumber.value.phoneCountryCode.value,
              ),
              isDefault = entry.isDefault,
            )
          ),
          addressLine1 = customerIndividualDetailsRow.addressLine1.map(_.value),
          addressLine2 = customerIndividualDetailsRow.addressLine2.map(_.value),
          city = customerIndividualDetailsRow.city.map(_.value),
          postalCode = customerIndividualDetailsRow.postalCode.map(_.value),
          country = customerIndividualDetailsRow.country.map(_.value),
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
          .getCustomerIndividualGet(organizationID.value, customerID.value)
          .zioError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Customer individual not found for customerID: [${customerID.value}]"
        )
      }
    }

    "getCustomerBusinessGet" should {
      "successfully return the customer business's full details" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]

        customerBookRepositoryMock.getCustomerBusiness
          .expects(organizationID, customerBusinessDetailsRow.customerID)
          .returningZIO(Some(customerBusinessDetailsRow))
          .once()

        val customerBookService = buildCustomerBookService

        val getCustomerBusinessGetResponse = customerBookService
          .getCustomerBusinessGet(organizationID.value, customerBusinessDetailsRow.customerID.value)
          .zioValue

        getCustomerBusinessGetResponse shouldBe smithy.GetCustomerBusinessGetResponse(
          customerID = customerBusinessDetailsRow.customerID.value,
          businessName = customerBusinessDetailsRow.businessName.value,
          emails = customerBusinessDetailsRow.emails.map(entry =>
            smithy.CustomerEmailEntryRequest(email = entry.email.value, isDefault = entry.isDefault)
          ),
          taxID = customerBusinessDetailsRow.taxID.map(_.value),
          phoneNumbers = customerBusinessDetailsRow.phoneNumbers.map(entry =>
            smithy.CustomerPhoneNumberEntryRequest(
              phoneNumber = smithy.PhoneNumberRequest(
                phoneNationalNumber = entry.phoneNumber.value.phoneNationalNumber.value,
                phoneCountryCode = entry.phoneNumber.value.phoneCountryCode.value,
              ),
              isDefault = entry.isDefault,
            )
          ),
          addressLine1 = customerBusinessDetailsRow.addressLine1.map(_.value),
          addressLine2 = customerBusinessDetailsRow.addressLine2.map(_.value),
          city = customerBusinessDetailsRow.city.map(_.value),
          postalCode = customerBusinessDetailsRow.postalCode.map(_.value),
          country = customerBusinessDetailsRow.country.map(_.value),
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
          .getCustomerBusinessGet(organizationID.value, customerID.value)
          .zioError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Customer business not found for customerID: [${customerID.value}]"
        )
      }
    }

    "getCustomersGet" should {
      "successfully return every customer in the repository's order" in new TestContext {
        val organizationID      = arbitrarySample[OrganizationID]
        val customerSummaryRow1 = arbitrarySample[CustomerSummaryRow]
        val customerSummaryRow2 = arbitrarySample[CustomerSummaryRow]

        customerSummaryRow1 should not be customerSummaryRow2

        customerBookRepositoryMock.getCustomers
          .expects(organizationID)
          .returningZIO(List(customerSummaryRow1, customerSummaryRow2))
          .once()

        val customerBookService = buildCustomerBookService

        val getCustomersGetResponse = customerBookService.getCustomersGet(organizationID.value).zioValue

        getCustomersGetResponse shouldBe smithy.GetCustomersGetResponse(
          customers = List(customerSummaryRow1, customerSummaryRow2).map(customerSummaryRow =>
            smithy.GetCustomer(
              customerID = customerSummaryRow.customerID.value,
              displayName = customerSummaryRow.displayName.value,
              customerType = customerSummaryRow.customerType match {
                case CustomerType.Individual => smithy.CustomerType.INDIVIDUAL
                case CustomerType.Business   => smithy.CustomerType.BUSINESS
              },
            )
          )
        )
      }

      "fail with a repository error and surface it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        customerBookRepositoryMock.getCustomers
          .expects(organizationID)
          .returns(ZIO.fail(repositoryError))
          .once()

        val customerBookService = buildCustomerBookService

        customerBookService.getCustomersGet(organizationID.value).zioError shouldBe repositoryError
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
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
          ZLayer.succeed(customerBookRepositoryMock),
        )
        .zioValue
  }
}
