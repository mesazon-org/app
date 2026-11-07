package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.*
import io.scalaland.chimney.dsl.*
import org.scalatest.DoNotDiscover
import smithy4s.Nullable
import sttp.model.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class CustomerBookApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      CustomerBookSmithyArbitraries,
      RepositoryArbitraries {

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      postgresClient.truncateTable(
        repositoryConfig.schema,
        repositoryConfig.userDetailsTable,
        repositoryConfig.organizationUserTable,
        repositoryConfig.customerTable,
        repositoryConfig.customerBusinessContactTable,
      )
    }
  }

  "Customer Book Service API" when {
    "POST /customer-book/individual" should {
      "successfully insert a customer individual" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]
          instantNow                        = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.InternalServerError](
              customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.Ok

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerIDsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIndividualDetailsRowsAll should have size 1
          _ = customerIDsAll should have size 1

          _ = customerIndividualDetailsRowsAll.head shouldBe CustomerIndividualDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookIndividualPostResponse.body.value.customerID),
            fullName = customerBookIndividualPostRequest.fullName,
            emails = customerBookIndividualPostRequest.emails.map(customerEmailEntry =>
              CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
            ),
            phoneNumbers = customerBookIndividualPostRequest.phoneNumbers.map(customerPhoneNumberEntry =>
              CustomerPhoneNumberEntryInput(
                customerPhoneNumberEntry.phoneNumber,
                isDefault = customerPhoneNumberEntry.isDefault,
              )
            ),
            addresses = customerBookIndividualPostRequest.addresses,
            isArchived = false,
            createdAt = customerIndividualDetailsRowsAll.head.createdAt,
            updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
          )
          _ =
            customerIndividualDetailsRowsAll.head.updatedAt.value shouldBe customerIndividualDetailsRowsAll.head.createdAt.value
          _ = customerIndividualDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

          _ = customerBookIndividualPostResponse.body.value shouldBe smithy.CustomerBookIndividualPostResponse(
            customerID = customerIndividualDetailsRowsAll.head.customerID.value
          )
        } yield ()
      }

      "successfully insert a customer individual with a partial address" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          addressPartial = arbitrarySample[Address].copy(addressLine1 = None, city = Some(City.assume("Limassol")))
          customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]
            .copy(addresses = List(CustomerAddressEntry(AddressEntry(addressPartial, AddressType.ShippingAndBilling))))
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.InternalServerError](
              customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.Ok

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerIDsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIndividualDetailsRowsAll should have size 1
          _ = customerIDsAll should have size 1

          _ = customerIndividualDetailsRowsAll.head shouldBe CustomerIndividualDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookIndividualPostResponse.body.value.customerID),
            fullName = customerBookIndividualPostRequest.fullName,
            emails = customerBookIndividualPostRequest.emails.map(customerEmailEntry =>
              CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
            ),
            phoneNumbers = customerBookIndividualPostRequest.phoneNumbers.map(customerPhoneNumberEntry =>
              CustomerPhoneNumberEntryInput(
                customerPhoneNumberEntry.phoneNumber,
                isDefault = customerPhoneNumberEntry.isDefault,
              )
            ),
            addresses = List(CustomerAddressEntry(AddressEntry(addressPartial, AddressType.ShippingAndBilling))),
            isArchived = false,
            createdAt = customerIndividualDetailsRowsAll.head.createdAt,
            updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
          )
          _ =
            customerIndividualDetailsRowsAll.head.updatedAt.value shouldBe customerIndividualDetailsRowsAll.head.createdAt.value
          _ = customerIndividualDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

          _ = customerBookIndividualPostResponse.body.value shouldBe smithy.CustomerBookIndividualPostResponse(
            customerID = customerIndividualDetailsRowsAll.head.customerID.value
          )
        } yield ()
      }

      "successfully insert a customer individual with only its required fields" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerFullName                        = CustomerFullName.assume("Maria Georgiou")
          customerBookIndividualPostRequestSmithy = smithy.CustomerBookIndividualPostRequest(fullName =
            customerFullName.value
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.InternalServerError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.Ok

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerIDsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIndividualDetailsRowsAll should have size 1
          _ = customerIDsAll should have size 1

          _ = customerIndividualDetailsRowsAll.head shouldBe CustomerIndividualDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookIndividualPostResponse.body.value.customerID),
            fullName = customerFullName,
            emails = Nil,
            phoneNumbers = Nil,
            addresses = Nil,
            isArchived = false,
            createdAt = customerIndividualDetailsRowsAll.head.createdAt,
            updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
          )
          _ =
            customerIndividualDetailsRowsAll.head.updatedAt.value shouldBe customerIndividualDetailsRowsAll.head.createdAt.value
          _ = customerIndividualDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

          _ = customerBookIndividualPostResponse.body.value shouldBe smithy.CustomerBookIndividualPostResponse(
            customerID = customerIndividualDetailsRowsAll.head.customerID.value
          )
        } yield ()
      }

      "fail with a ValidationError when the full name is invalid" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
            .copy(fullName = "")

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.ValidationError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookIndividualPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("fullName")
          )

          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIDsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError when an address has no fields" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
            .copy(addresses =
              List(
                smithy.CustomerAddressEntry(
                  address = smithy.AddressRequest(),
                  addressType = smithy.AddressType.ShippingAndBilling,
                )
              )
            )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.ValidationError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookIndividualPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("address")
          )

          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIDsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError when the address types are not an allowed combination" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .copy(addresses =
              List(
                CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.Shipping)),
                CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.Shipping)),
              )
            )
            .transformInto[smithy.CustomerBookIndividualPostRequest]

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.ValidationError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookIndividualPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("addresses")
          )

          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIDsAll shouldBe Nil
        } yield ()
      }

      "fail with a Conflict when the full name already exists in the organization" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookIndividualPostRequest    = arbitrarySample[CustomerBookIndividualPostRequest]
          customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              fullName = customerBookIndividualPostRequest.fullName,
            )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPostResponse <- gatewayClient
            .customerBookIndividualPost[smithy.Conflict](
              customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPostResponse.code shouldBe StatusCode.Conflict
          _ = customerBookIndividualPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A customer with the given name already exists in this organization"
          )

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)

          _ = customerIDsAll shouldBe
            List(customerIndividualDetailsRowExisting.customerID)
          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRowExisting)
        } yield ()
      }
    }

    "POST /customer-book/business" should {
      "successfully insert a customer business with its inline contact" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessContactInsert   = arbitrarySample[CustomerBusinessContactInsert]
          customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = List(customerBusinessContactInsert))
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPostResponse <- gatewayClient
            .customerBookBusinessPost[smithy.InternalServerError](
              customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerIDsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerBusinessDetailsRowsAll should have size 1
          _ = customerIDsAll should have size 1

          _ = customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
            businessName = customerBookBusinessPostRequest.businessName,
            emails = customerBookBusinessPostRequest.emails.map(customerEmailEntry =>
              CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
            ),
            phoneNumbers = customerBookBusinessPostRequest.phoneNumbers.map(customerPhoneNumberEntry =>
              CustomerPhoneNumberEntryInput(
                customerPhoneNumberEntry.phoneNumber,
                isDefault = customerPhoneNumberEntry.isDefault,
              )
            ),
            taxID = customerBookBusinessPostRequest.taxID,
            addresses = customerBookBusinessPostRequest.addresses,
            isArchived = false,
            createdAt = customerBusinessDetailsRowsAll.head.createdAt,
            updatedAt = customerBusinessDetailsRowsAll.head.updatedAt,
          )
          _ =
            customerBusinessDetailsRowsAll.head.updatedAt.value shouldBe customerBusinessDetailsRowsAll.head.createdAt.value
          _ = customerBusinessDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

          _ =
            customerBusinessContactRowsAll should have size customerBookBusinessPostRequest.customerBusinessContacts.size
          _ = customerBusinessContactRowsAll.head shouldBe CustomerBusinessContactRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
            customerBusinessContactID = customerBusinessContactRowsAll.head.customerBusinessContactID,
            fullName = customerBusinessContactInsert.fullName,
            role = customerBusinessContactInsert.role,
            email = customerBusinessContactInsert.email,
            phoneNumber = customerBusinessContactInsert.phoneNumber,
            createdAt = customerBusinessContactRowsAll.head.createdAt,
            updatedAt = customerBusinessContactRowsAll.head.updatedAt,
            isArchived = false,
          )
          _ =
            customerBusinessContactRowsAll.head.createdAt.value shouldBe customerBusinessDetailsRowsAll.head.createdAt.value
          _ =
            customerBusinessContactRowsAll.head.updatedAt.value shouldBe customerBusinessDetailsRowsAll.head.createdAt.value

          _ = customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
            customerID = customerBusinessDetailsRowsAll.head.customerID.value
          )
        } yield ()
      }

      "successfully insert both inline contacts sharing the same email" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerEmail                  = arbitrarySample[CustomerEmail]
          customerBusinessContactInsert1 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(fullName = CustomerFullName.assume("contact one"), email = Some(customerEmail), phoneNumber = None)
          customerBusinessContactInsert2 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(fullName = CustomerFullName.assume("contact two"), email = Some(customerEmail), phoneNumber = None)
          customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = List(customerBusinessContactInsert1, customerBusinessContactInsert2))
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPostResponse <- gatewayClient
            .customerBookBusinessPost[smithy.InternalServerError](
              customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )
          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerBusinessDetailsRowsAll should have size 1

          _ =
            customerBusinessContactRowsAll should have size customerBookBusinessPostRequest.customerBusinessContacts.size

          _ = customerIDsAll shouldBe List(
            CustomerID(customerBookBusinessPostResponse.body.value.customerID)
          )

          _ = customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
            businessName = customerBookBusinessPostRequest.businessName,
            taxID = customerBookBusinessPostRequest.taxID,
            emails = customerBookBusinessPostRequest.emails.map(customerEmailEntry =>
              CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
            ),
            phoneNumbers = customerBookBusinessPostRequest.phoneNumbers.map(customerPhoneNumberEntry =>
              CustomerPhoneNumberEntryInput(
                customerPhoneNumberEntry.phoneNumber,
                isDefault = customerPhoneNumberEntry.isDefault,
              )
            ),
            addresses = customerBookBusinessPostRequest.addresses,
            isArchived = false,
            createdAt = customerBusinessDetailsRowsAll.head.createdAt,
            updatedAt = UpdatedAt(customerBusinessDetailsRowsAll.head.createdAt.value),
          )
          _ = customerBusinessDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

          _ =
            customerBusinessContactRowsAll should contain theSameElementsAs customerBookBusinessPostRequest.customerBusinessContacts.map {
              customerBusinessContactInsert =>
                val customerBusinessContactRow =
                  customerBusinessContactRowsAll.find(_.fullName == customerBusinessContactInsert.fullName).value
                CustomerBusinessContactRow(
                  organizationID = organizationUserRow.organizationID,
                  customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
                  customerBusinessContactID = customerBusinessContactRow.customerBusinessContactID,
                  fullName = customerBusinessContactInsert.fullName,
                  role = customerBusinessContactInsert.role,
                  email = customerBusinessContactInsert.email,
                  phoneNumber = customerBusinessContactInsert.phoneNumber,
                  createdAt = customerBusinessDetailsRowsAll.head.createdAt,
                  updatedAt = UpdatedAt(customerBusinessDetailsRowsAll.head.createdAt.value),
                  isArchived = false,
                )
            }

          _ = customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
            customerID = customerBusinessDetailsRowsAll.head.customerID.value
          )
        } yield ()
      }

      "successfully insert both inline contacts sharing the same phone number" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerPhoneNumber            = arbitrarySample[CustomerPhoneNumber]
          customerBusinessContactInsert1 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(
              fullName = CustomerFullName.assume("contact one"),
              email = None,
              phoneNumber = Some(customerPhoneNumber),
            )
          customerBusinessContactInsert2 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(
              fullName = CustomerFullName.assume("contact two"),
              email = None,
              phoneNumber = Some(customerPhoneNumber),
            )
          customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = List(customerBusinessContactInsert1, customerBusinessContactInsert2))
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPostResponse <- gatewayClient
            .customerBookBusinessPost[smithy.InternalServerError](
              customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )
          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerBusinessDetailsRowsAll should have size 1

          _ =
            customerBusinessContactRowsAll should have size customerBookBusinessPostRequest.customerBusinessContacts.size

          _ = customerIDsAll shouldBe List(
            CustomerID(customerBookBusinessPostResponse.body.value.customerID)
          )

          _ = customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
            businessName = customerBookBusinessPostRequest.businessName,
            taxID = customerBookBusinessPostRequest.taxID,
            emails = customerBookBusinessPostRequest.emails.map(customerEmailEntry =>
              CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
            ),
            phoneNumbers = customerBookBusinessPostRequest.phoneNumbers.map(customerPhoneNumberEntry =>
              CustomerPhoneNumberEntryInput(
                customerPhoneNumberEntry.phoneNumber,
                isDefault = customerPhoneNumberEntry.isDefault,
              )
            ),
            addresses = customerBookBusinessPostRequest.addresses,
            isArchived = false,
            createdAt = customerBusinessDetailsRowsAll.head.createdAt,
            updatedAt = UpdatedAt(customerBusinessDetailsRowsAll.head.createdAt.value),
          )
          _ = customerBusinessDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

          _ =
            customerBusinessContactRowsAll should contain theSameElementsAs customerBookBusinessPostRequest.customerBusinessContacts.map {
              customerBusinessContactInsert =>
                val customerBusinessContactRow =
                  customerBusinessContactRowsAll.find(_.fullName == customerBusinessContactInsert.fullName).value
                CustomerBusinessContactRow(
                  organizationID = organizationUserRow.organizationID,
                  customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
                  customerBusinessContactID = customerBusinessContactRow.customerBusinessContactID,
                  fullName = customerBusinessContactInsert.fullName,
                  role = customerBusinessContactInsert.role,
                  email = customerBusinessContactInsert.email,
                  phoneNumber = customerBusinessContactInsert.phoneNumber,
                  createdAt = customerBusinessDetailsRowsAll.head.createdAt,
                  updatedAt = UpdatedAt(customerBusinessDetailsRowsAll.head.createdAt.value),
                  isArchived = false,
                )
            }

          _ = customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
            customerID = customerBusinessDetailsRowsAll.head.customerID.value
          )
        } yield ()
      }

      "successfully insert a customer business with only its required fields and a contact without email or phone number" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessName                  = CustomerBusinessName.assume("Acme Trading")
            customerBusinessContactFullName       = CustomerFullName.assume("Nikos Ioannou")
            customerBookBusinessPostRequestSmithy = smithy.CustomerBookBusinessPostRequest(
              businessName = customerBusinessName.value,
              customerBusinessContacts =
                List(smithy.CustomerBusinessContactInsert(fullName = customerBusinessContactFullName.value)),
            )
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPostResponse <- gatewayClient
              .customerBookBusinessPost[smithy.InternalServerError](
                customerBookBusinessPostRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerIDsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerBusinessDetailsRowsAll should have size 1
            _ = customerIDsAll should have size 1

            _ = customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
              businessName = customerBusinessName,
              emails = Nil,
              phoneNumbers = Nil,
              taxID = None,
              addresses = Nil,
              isArchived = false,
              createdAt = customerBusinessDetailsRowsAll.head.createdAt,
              updatedAt = customerBusinessDetailsRowsAll.head.updatedAt,
            )
            _ =
              customerBusinessDetailsRowsAll.head.updatedAt.value shouldBe customerBusinessDetailsRowsAll.head.createdAt.value
            _ = customerBusinessDetailsRowsAll.head.createdAt.value.isBefore(instantNow) shouldBe false

            _ = customerBusinessContactRowsAll should have size 1
            _ = customerBusinessContactRowsAll.head shouldBe CustomerBusinessContactRow(
              organizationID = organizationUserRow.organizationID,
              customerID = CustomerID(customerBookBusinessPostResponse.body.value.customerID),
              customerBusinessContactID = customerBusinessContactRowsAll.head.customerBusinessContactID,
              fullName = customerBusinessContactFullName,
              role = None,
              email = None,
              phoneNumber = None,
              createdAt = customerBusinessContactRowsAll.head.createdAt,
              updatedAt = customerBusinessContactRowsAll.head.updatedAt,
              isArchived = false,
            )
            _ =
              customerBusinessContactRowsAll.head.createdAt.value shouldBe customerBusinessDetailsRowsAll.head.createdAt.value
            _ =
              customerBusinessContactRowsAll.head.updatedAt.value shouldBe customerBusinessDetailsRowsAll.head.createdAt.value

            _ = customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
              customerID = customerBusinessDetailsRowsAll.head.customerID.value
            )
          } yield ()
      }

      "fail with a ValidationError when the business name is invalid" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
            .transformInto[smithy.CustomerBookBusinessPostRequest]
            .copy(businessName = "")

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPostResponse <- gatewayClient
            .customerBookBusinessPost[smithy.ValidationError](
              customerBookBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookBusinessPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("businessName")
          )

          customerIDsAll                 <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a Conflict when the business name already exists in the organization" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessContactInsert   = arbitrarySample[CustomerBusinessContactInsert]
          customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = List(customerBusinessContactInsert))
          customerBusinessDetailsRowExisting = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              businessName = customerBookBusinessPostRequest.businessName,
            )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRowExisting))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPostResponse <- gatewayClient
            .customerBookBusinessPost[smithy.Conflict](
              customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPostResponse.code shouldBe StatusCode.Conflict
          _ = customerBookBusinessPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A customer with the given name already exists in this organization"
          )

          customerIDsAll                 <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe
            List(customerBusinessDetailsRowExisting.customerID)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRowExisting)
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }
    }

    "POST /customer-book/batch" should {
      "successfully insert individuals before businesses in the response and persist every customer and inline contact" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerFullName1                  = CustomerFullName.assume("Zeta Individual")
            customerFullName2                  = CustomerFullName.assume("Alpha Individual")
            customerBusinessName1              = CustomerBusinessName.assume("Zeta Business")
            customerBusinessName2              = CustomerBusinessName.assume("Alpha Business")
            customerBusinessContactInsert      = arbitrarySample[CustomerBusinessContactInsert]
            customerBookIndividualPostRequest1 = arbitrarySample[CustomerBookIndividualPostRequest]
              .copy(fullName = customerFullName1)
            customerBookIndividualPostRequest2 = arbitrarySample[CustomerBookIndividualPostRequest]
              .copy(fullName = customerFullName2)
            customerBookBusinessPostRequest1 = arbitrarySample[CustomerBookBusinessPostRequest]
              .copy(businessName = customerBusinessName1)
              .copy(customerBusinessContacts = List(customerBusinessContactInsert))
            customerBookBusinessPostRequest2 = arbitrarySample[CustomerBookBusinessPostRequest]
              .copy(businessName = customerBusinessName2, customerBusinessContacts = List.empty)
            customerIndividuals          = List(customerBookIndividualPostRequest1, customerBookIndividualPostRequest2)
            customerBusinesses           = List(customerBookBusinessPostRequest1, customerBookBusinessPostRequest2)
            customerBookBatchPostRequest = CustomerBookBatchPostRequest(
              customerBusinesses = customerBusinesses,
              customerIndividuals = customerIndividuals,
            )
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBatchPostResponse <- gatewayClient.customerBookBatchPost[smithy.InternalServerError](
              customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = customerBookBatchPostResponse.code shouldBe StatusCode.Ok

            customerIndividualDetailsRows <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRows <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRows <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )
            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

            _ = customerIndividualDetailsRows should have size customerIndividuals.size

            _ = customerBusinessDetailsRows should have size customerBusinesses.size

            _ = customerBusinessContactRows should have size customerBusinesses.flatMap(_.customerBusinessContacts).size

            _ = customerIDsAll should have size (customerIndividuals.size + customerBusinesses.size)

            customerIndividualDetailsRow1 = customerIndividualDetailsRows.find(_.fullName == customerFullName1).value
            customerIndividualDetailsRow2 = customerIndividualDetailsRows.find(_.fullName == customerFullName2).value
            customerBusinessDetailsRow1   = customerBusinessDetailsRows
              .find(_.businessName == customerBusinessName1)
              .value
            customerBusinessDetailsRow2 = customerBusinessDetailsRows
              .find(_.businessName == customerBusinessName2)
              .value

            _ = customerBookBatchPostResponse.body.value shouldBe smithy.CustomerBookBatchPostResponse(
              customerIDs = List(
                customerIndividualDetailsRow1.customerID.value,
                customerIndividualDetailsRow2.customerID.value,
                customerBusinessDetailsRow1.customerID.value,
                customerBusinessDetailsRow2.customerID.value,
              )
            )

            _ = customerIndividualDetailsRow1 shouldBe CustomerIndividualDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerIndividualDetailsRow1.customerID,
              fullName = customerBookIndividualPostRequest1.fullName,
              emails = customerBookIndividualPostRequest1.emails.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              ),
              phoneNumbers = customerBookIndividualPostRequest1.phoneNumbers.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              ),
              addresses = customerBookIndividualPostRequest1.addresses,
              isArchived = false,
              createdAt = customerIndividualDetailsRow1.createdAt,
              updatedAt = customerIndividualDetailsRow1.updatedAt,
            )
            _ = customerIndividualDetailsRow1.updatedAt.value shouldBe customerIndividualDetailsRow1.createdAt.value
            _ = customerIndividualDetailsRow1.createdAt.value.isBefore(instantNow) shouldBe false

            _ = customerIndividualDetailsRow2 shouldBe CustomerIndividualDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerIndividualDetailsRow2.customerID,
              fullName = customerBookIndividualPostRequest2.fullName,
              emails = customerBookIndividualPostRequest2.emails.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              ),
              phoneNumbers = customerBookIndividualPostRequest2.phoneNumbers.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              ),
              addresses = customerBookIndividualPostRequest2.addresses,
              isArchived = false,
              createdAt = customerIndividualDetailsRow2.createdAt,
              updatedAt = customerIndividualDetailsRow2.updatedAt,
            )
            _ = customerIndividualDetailsRow2.createdAt.value shouldBe customerIndividualDetailsRow1.createdAt.value
            _ = customerIndividualDetailsRow2.updatedAt.value shouldBe customerIndividualDetailsRow1.createdAt.value

            _ = customerBusinessDetailsRow1 shouldBe CustomerBusinessDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerBusinessDetailsRow1.customerID,
              businessName = customerBookBusinessPostRequest1.businessName,
              emails = customerBookBusinessPostRequest1.emails.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              ),
              phoneNumbers = customerBookBusinessPostRequest1.phoneNumbers.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              ),
              taxID = customerBookBusinessPostRequest1.taxID,
              addresses = customerBookBusinessPostRequest1.addresses,
              isArchived = false,
              createdAt = customerBusinessDetailsRow1.createdAt,
              updatedAt = customerBusinessDetailsRow1.updatedAt,
            )
            _ = customerBusinessDetailsRow1.createdAt.value shouldBe customerIndividualDetailsRow1.createdAt.value
            _ = customerBusinessDetailsRow1.updatedAt.value shouldBe customerIndividualDetailsRow1.createdAt.value

            _ = customerBusinessDetailsRow2 shouldBe CustomerBusinessDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerBusinessDetailsRow2.customerID,
              businessName = customerBookBusinessPostRequest2.businessName,
              emails = customerBookBusinessPostRequest2.emails.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              ),
              phoneNumbers = customerBookBusinessPostRequest2.phoneNumbers.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              ),
              taxID = customerBookBusinessPostRequest2.taxID,
              addresses = customerBookBusinessPostRequest2.addresses,
              isArchived = false,
              createdAt = customerBusinessDetailsRow2.createdAt,
              updatedAt = customerBusinessDetailsRow2.updatedAt,
            )
            _ = customerBusinessDetailsRow2.createdAt.value shouldBe customerIndividualDetailsRow1.createdAt.value
            _ = customerBusinessDetailsRow2.updatedAt.value shouldBe customerIndividualDetailsRow1.createdAt.value

            _ = customerBusinessContactRows shouldBe List(
              CustomerBusinessContactRow(
                organizationID = organizationUserRow.organizationID,
                customerID = customerBusinessDetailsRow1.customerID,
                customerBusinessContactID = customerBusinessContactRows.head.customerBusinessContactID,
                fullName = customerBusinessContactInsert.fullName,
                role = customerBusinessContactInsert.role,
                email = customerBusinessContactInsert.email,
                phoneNumber = customerBusinessContactInsert.phoneNumber,
                createdAt = customerBusinessContactRows.head.createdAt,
                updatedAt = customerBusinessContactRows.head.updatedAt,
                isArchived = false,
              )
            )
            _ = customerBusinessContactRows.head.createdAt.value shouldBe customerIndividualDetailsRow1.createdAt.value
            _ = customerBusinessContactRows.head.updatedAt.value shouldBe customerIndividualDetailsRow1.createdAt.value
          } yield ()
      }

      "successfully insert an empty batch without persisting customers or contacts" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          customerBookBatchPostRequestSmithy = smithy.CustomerBookBatchPostRequest(
            customerBusinesses = Nil,
            customerIndividuals = Nil,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBatchPostResponse <- gatewayClient
            .customerBookBatchPost[smithy.InternalServerError](
              customerBookBatchPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBatchPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookBatchPostResponse.body.value shouldBe smithy.CustomerBookBatchPostResponse(customerIDs = Nil)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError for invalid businesses and individuals without persisting any customer" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
              .copy(customerBusinessContacts = Nil)
              .transformInto[smithy.CustomerBookBusinessPostRequest]
            customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
              .transformInto[smithy.CustomerBookIndividualPostRequest]
            customerBookBatchPostRequestSmithy = smithy.CustomerBookBatchPostRequest(
              customerBusinesses = List(customerBookBusinessPostRequestSmithy.copy(businessName = "")),
              customerIndividuals = List(customerBookIndividualPostRequestSmithy.copy(fullName = "")),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBatchPostResponse <- gatewayClient
              .customerBookBatchPost[smithy.ValidationError](
                customerBookBatchPostRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBatchPostResponse.code shouldBe StatusCode.BadRequest
            _ = customerBookBatchPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
              List("customerBusiness", "customerIndividual")
            )

            customerIDsAll                 <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIDsAll shouldBe Nil
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }

      "fail with a Conflict for an existing individual name and leave the accompanying business unpersisted" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
            )

            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting)
              )

            customerBusinessContactInsert = arbitrarySample[CustomerBusinessContactInsert]
            customerBookBatchPostRequest  = CustomerBookBatchPostRequest(
              customerBusinesses = List(
                arbitrarySample[CustomerBookBusinessPostRequest]
                  .copy(customerBusinessContacts = List(customerBusinessContactInsert))
              ),
              customerIndividuals = List(
                arbitrarySample[CustomerBookIndividualPostRequest]
                  .copy(fullName = customerIndividualDetailsRowExisting.fullName)
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBatchPostResponse <- gatewayClient
              .customerBookBatchPost[smithy.Conflict](
                customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBatchPostResponse.code shouldBe StatusCode.Conflict
            _ = customerBookBatchPostResponse.body.left.value shouldBe smithy.Conflict(
              message = "A customer with the given name already exists in this organization"
            )

            customerIDsAll                 <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)

            _ = customerIDsAll shouldBe
              List(customerIndividualDetailsRowExisting.customerID)
            _ = customerBusinessDetailsRowsAll shouldBe Nil
            _ = customerBusinessContactRowsAll shouldBe Nil
            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRowExisting)
          } yield ()
      }

      "fail with a Conflict for an existing business name and roll back the accompanying individual" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRowExisting = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
            )

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRowExisting))

            customerBusinessContactInsert = arbitrarySample[CustomerBusinessContactInsert]
            customerBookBatchPostRequest  = CustomerBookBatchPostRequest(
              customerBusinesses = List(
                arbitrarySample[CustomerBookBusinessPostRequest]
                  .copy(
                    businessName = customerBusinessDetailsRowExisting.businessName,
                    customerBusinessContacts = List(customerBusinessContactInsert),
                  )
              ),
              customerIndividuals = List(
                arbitrarySample[CustomerBookIndividualPostRequest]
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBatchPostResponse <- gatewayClient
              .customerBookBatchPost[smithy.Conflict](
                customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBatchPostResponse.code shouldBe StatusCode.Conflict
            _ = customerBookBatchPostResponse.body.left.value shouldBe smithy.Conflict(
              message = "A customer with the given name already exists in this organization"
            )

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)

            _ = customerIDsAll shouldBe
              List(customerBusinessDetailsRowExisting.customerID)
            _ = customerIndividualDetailsRowsAll shouldBe Nil
            _ = customerBusinessContactRowsAll shouldBe Nil
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRowExisting)
          } yield ()
      }
    }

    "PUT /customer-book/individual/{customerID}" should {
      "successfully replace the name and the email, phone number and address lists" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            )
          customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]
            .copy(
              emails = List(CustomerEmailEntry(arbitrarySample[CustomerEmail], isDefault = true)),
              phoneNumbers = List(CustomerPhoneNumberEntry(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          customerBookIndividualPutRequestSmithy = CustomerBookIndividualPutRequest(
            fullName = Some(customerBookIndividualPostRequest.fullName),
            emails = Some(customerBookIndividualPostRequest.emails),
            phoneNumbers = Some(customerBookIndividualPostRequest.phoneNumbers),
            addresses = Some(customerBookIndividualPostRequest.addresses),
          ).transformInto[smithy.CustomerBookIndividualPutRequest]
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(
            customerIndividualDetailsRow.copy(
              fullName = customerBookIndividualPostRequest.fullName,
              emails = customerBookIndividualPostRequest.emails.map(customerEmailEntry =>
                CustomerEmailEntryInput(customerEmailEntry.email, isDefault = customerEmailEntry.isDefault)
              ),
              phoneNumbers = customerBookIndividualPostRequest.phoneNumbers.map(customerPhoneNumberEntry =>
                CustomerPhoneNumberEntryInput(
                  customerPhoneNumberEntry.phoneNumber,
                  isDefault = customerPhoneNumberEntry.isDefault,
                )
              ),
              addresses = customerBookIndividualPostRequest.addresses,
              updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
            )
          )
          _ = customerIndividualDetailsRowsAll.head should not equal customerIndividualDetailsRow
          _ = customerIndividualDetailsRowsAll.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerIDsAll shouldBe List(
            customerIndividualDetailsRow.customerID
          )
        } yield ()
      }

      "successfully retain omitted lists when updating the name" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            )
          customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(fullName =
            Some(customerBookIndividualPostRequest.fullName.value)
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(
            customerIndividualDetailsRow.copy(
              fullName = customerBookIndividualPostRequest.fullName,
              updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
            )
          )
          _ = customerIndividualDetailsRowsAll.head should not equal customerIndividualDetailsRow
          _ = customerIndividualDetailsRowsAll.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerIDsAll shouldBe List(
            customerIndividualDetailsRow.customerID
          )
        } yield ()
      }

      "successfully clear explicitly empty lists while retaining the omitted name" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
            emails = Some(Nil),
            phoneNumbers = Some(Nil),
            addresses = Some(Nil),
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(
            customerIndividualDetailsRow.copy(
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
            )
          )
          _ = customerIndividualDetailsRowsAll.head should not equal customerIndividualDetailsRow
          _ = customerIndividualDetailsRowsAll.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerIDsAll shouldBe List(
            customerIndividualDetailsRow.customerID
          )
        } yield ()
      }

      "successfully do nothing when the customer individual does not exist" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              smithy.CustomerBookIndividualPutRequest(
                fullName = Some("changed name"),
                emails = Some(Nil),
                phoneNumbers = Some(Nil),
                addresses = Some(Nil),
              ),
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)

          _ = customerIDsAll shouldBe Nil
        } yield ()
      }

      "successfully leave an archived customer individual unchanged" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationUserRow.organizationID, isArchived = true)

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              smithy.CustomerBookIndividualPutRequest(
                fullName = Some("changed name"),
                emails = Some(Nil),
                phoneNumbers = Some(Nil),
                addresses = Some(Nil),
              ),
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
        } yield ()
      }

      "successfully leave another organization's customer individual unchanged" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(isArchived = false)

          _ = customerIndividualDetailsRow.organizationID should not be organizationUserRow.organizationID

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              smithy.CustomerBookIndividualPutRequest(
                fullName = Some("changed name"),
                emails = Some(Nil),
                phoneNumbers = Some(Nil),
                addresses = Some(Nil),
              ),
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
        } yield ()
      }

      "successfully leave a customer business targeted as an individual unchanged" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationUserRow.organizationID, isArchived = false)

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              smithy.CustomerBookIndividualPutRequest(
                fullName = Some("changed name"),
                emails = Some(Nil),
                phoneNumbers = Some(Nil),
                addresses = Some(Nil),
              ),
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookIndividualPutResponse.body.value shouldBe ()

          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)

          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerIndividualDetailsRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError when the full name is invalid" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationUserRow.organizationID, isArchived = false)

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )

          customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(fullName = Some(""))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualPutResponse <- gatewayClient
            .customerBookIndividualPut[smithy.ValidationError](
              customerIndividualDetailsRow.customerID,
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualPutResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookIndividualPutResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("fullName")
          )

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
        } yield ()
      }

      "fail with a Conflict when renaming to an active individual name and retain both complete rows" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(
                organizationID = organizationUserRow.organizationID,
                isArchived = false,
                fullName = CustomerFullName.assume("first customer"),
              )
            customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(
                organizationID = organizationUserRow.organizationID,
                isArchived = false,
                fullName = CustomerFullName.assume("second customer"),
              )

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting)
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookIndividualPutResponse <- gatewayClient
              .customerBookIndividualPut[smithy.Conflict](
                customerIndividualDetailsRow.customerID,
                smithy.CustomerBookIndividualPutRequest(
                  fullName = Some(customerIndividualDetailsRowExisting.fullName.value),
                  emails = Some(Nil),
                  phoneNumbers = Some(Nil),
                  addresses = Some(Nil),
                ),
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookIndividualPutResponse.code shouldBe StatusCode.Conflict
            _ = customerBookIndividualPutResponse.body.left.value shouldBe smithy.Conflict(message =
              "A customer with the given name already exists in this organization"
            )

            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )

            _ = customerIndividualDetailsRowsAll should contain theSameElementsAs List(
              customerIndividualDetailsRow,
              customerIndividualDetailsRowExisting,
            )
          } yield ()
      }
    }

    "PUT /customer-book/business/{customerID}" should {
      "successfully replace business fields and atomically insert update and archive contacts while retaining unnamed contacts" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(
                CustomerPhoneNumber(
                  PhoneNumber(
                    PhoneRegion.assume("CY"),
                    PhoneCountryCode.assume("+357"),
                    PhoneNationalNumber.assume("99123123"),
                    PhoneNumberE164.assume("+35799123123"),
                  )
                )
              ),
              isArchived = false,
            )
            customerBusinessContactRowArchive = customerBusinessContactRow.copy(customerBusinessContactID =
              arbitrarySample[CustomerBusinessContactID]
            )
            customerBusinessContactRowRetained = customerBusinessContactRow.copy(customerBusinessContactID =
              arbitrarySample[CustomerBusinessContactID]
            )
            customerBusinessContactRowAlreadyArchived = customerBusinessContactRow.copy(
              customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
              isArchived = true,
            )
            customerBusinessContactRows = List(
              customerBusinessContactRow,
              customerBusinessContactRowArchive,
              customerBusinessContactRowRetained,
              customerBusinessContactRowAlreadyArchived,
            )

            _ = customerBusinessContactRows
              .map(_.customerBusinessContactID)
              .distinct
              .size shouldBe customerBusinessContactRows.size

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))

            customerEmailReplacement       = CustomerEmail.assume("shared-replacement@example.com")
            customerPhoneNumberReplacement = CustomerPhoneNumber(
              PhoneNumber(
                PhoneRegion.assume("GB"),
                PhoneCountryCode.assume("+44"),
                PhoneNationalNumber.assume("7754767565"),
                PhoneNumberE164.assume("+447754767565"),
              )
            )
            customerBusinessContactInsert = CustomerBusinessContactInsert(
              CustomerFullName.assume("Inserted Contact"),
              customerBusinessContactRow.role,
              Some(customerEmailReplacement),
              Some(customerPhoneNumberReplacement),
            )
            customerBookBusinessPutRequest = CustomerBookBusinessPutRequest(
              Some(CustomerBusinessName.assume("Replacement Business")),
              Some(List(CustomerEmailEntry(CustomerEmail.assume("replacement@example.com"), isDefault = true))),
              Some(Some(CustomerTaxID.assume("ReplacementTax"))),
              Some(List(CustomerPhoneNumberEntry(arbitrarySample[CustomerPhoneNumber], isDefault = true))),
              Some(List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling)))),
              List(customerBusinessContactInsert),
              List(
                CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID,
                  Some(CustomerFullName.assume("Updated Contact")),
                  Some(Some(CustomerBusinessContactRole.assume("Updated Role"))),
                  Some(Some(customerEmailReplacement)),
                  Some(Some(customerPhoneNumberReplacement)),
                )
              ),
              List(CustomerBusinessContactArchive(customerBusinessContactRowArchive.customerBusinessContactID)),
            )
            customerBookBusinessPutRequestSmithy = customerBookBusinessPutRequest
              .transformInto[smithy.CustomerBookBusinessPutRequest]
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
            _ = customerBookBusinessPutResponse.body.value shouldBe ()

            customerBusinessDetailsRows <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )

            customerBusinessDetailsRowUpdated = customerBusinessDetailsRows.head

            _ = customerBusinessDetailsRows shouldBe List(
              customerBusinessDetailsRow.copy(
                businessName = CustomerBusinessName.assume("Replacement Business"),
                emails =
                  List(CustomerEmailEntryInput(CustomerEmail.assume("replacement@example.com"), isDefault = true)),
                taxID = Some(CustomerTaxID.assume("ReplacementTax")),
                phoneNumbers = customerBookBusinessPutRequest.phoneNumbers.value.map(customerPhoneNumberEntry =>
                  CustomerPhoneNumberEntryInput(
                    customerPhoneNumberEntry.phoneNumber,
                    isDefault = customerPhoneNumberEntry.isDefault,
                  )
                ),
                addresses = customerBookBusinessPutRequest.addresses.value,
                updatedAt = customerBusinessDetailsRowUpdated.updatedAt,
              )
            )

            _ = customerBusinessDetailsRowUpdated should not equal customerBusinessDetailsRow
            _ = customerBusinessDetailsRowUpdated.updatedAt.value.isBefore(instantNow) shouldBe false

            customerBusinessContactRowsPersisted <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            customerBusinessContactRowInserted = customerBusinessContactRowsPersisted
              .find(_.fullName == customerBusinessContactInsert.fullName)
              .value
            customerBusinessContactRowUpdated = customerBusinessContactRowsPersisted
              .find(
                _.customerBusinessContactID == customerBusinessContactRow.customerBusinessContactID
              )
              .value

            _ = customerBusinessContactRowUpdated.email shouldNot equal(customerBusinessContactRow.email)
            _ = customerBusinessContactRowUpdated.phoneNumber shouldNot equal(customerBusinessContactRow.phoneNumber)

            _ = customerBusinessContactRowInserted.email shouldBe customerBusinessContactRowUpdated.email
            _ = customerBusinessContactRowInserted.phoneNumber shouldBe customerBusinessContactRowUpdated.phoneNumber

            _ = customerBusinessContactRowsPersisted should contain theSameElementsAs List(
              customerBusinessContactRow.copy(
                fullName = CustomerFullName.assume("Updated Contact"),
                role = Some(CustomerBusinessContactRole.assume("Updated Role")),
                email = Some(customerEmailReplacement),
                phoneNumber = Some(customerPhoneNumberReplacement),
                updatedAt = customerBusinessDetailsRowUpdated.updatedAt,
              ),
              customerBusinessContactRowArchive
                .copy(isArchived = true, updatedAt = customerBusinessDetailsRowUpdated.updatedAt),
              customerBusinessContactRowRetained,
              customerBusinessContactRowAlreadyArchived,
              CustomerBusinessContactRow(
                customerBusinessDetailsRow.organizationID,
                customerBusinessDetailsRow.customerID,
                customerBusinessContactRowInserted.customerBusinessContactID,
                customerBusinessContactInsert.fullName,
                customerBusinessContactInsert.role,
                customerBusinessContactInsert.email,
                customerBusinessContactInsert.phoneNumber,
                CreatedAt(customerBusinessDetailsRowUpdated.updatedAt.value),
                customerBusinessDetailsRowUpdated.updatedAt,
                isArchived = false,
              ),
            )
          } yield ()
      }

      "successfully keep omitted fields and default empty contact actions" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest()
          instantNow                           = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPutResponse <- gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookBusinessPutResponse.body.value shouldBe ()

          customerBusinessDetailsRows <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerBusinessDetailsRows shouldBe List(
            customerBusinessDetailsRow.copy(updatedAt = customerBusinessDetailsRows.head.updatedAt)
          )
          _ = customerBusinessDetailsRows.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "successfully clear supplied empty customer lists and nullable tax and contact fields" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            emails = Some(Nil),
            taxID = Some(Nullable.Null),
            phoneNumbers = Some(Nil),
            addresses = Some(Nil),
            customerBusinessContactsUpdate = List(
              smithy.CustomerBusinessContactUpdate(
                customerBusinessContactRow.customerBusinessContactID.value,
                role = Some(Nullable.Null),
                email = Some(Nullable.Null),
                phoneNumber = Some(Nullable.Null),
              )
            ),
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPutResponse <- gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookBusinessPutResponse.body.value shouldBe ()

          customerBusinessDetailsRows <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRows <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerBusinessDetailsRows shouldBe List(
            customerBusinessDetailsRow.copy(
              taxID = None,
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              updatedAt = customerBusinessDetailsRows.head.updatedAt,
            )
          )
          _ = customerBusinessDetailsRows.head should not equal customerBusinessDetailsRow
          _ = customerBusinessDetailsRows.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerBusinessContactRows shouldBe List(
            customerBusinessContactRow.copy(
              role = None,
              email = None,
              phoneNumber = None,
              updatedAt = customerBusinessDetailsRows.head.updatedAt,
            )
          )
          _ = customerBusinessContactRows.head should not equal customerBusinessContactRow
        } yield ()
      }

      "successfully leave the business and its contacts unchanged when the customer business does not exist" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
            customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

            _ = customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate =
                List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
              customerBusinessContactsArchive =
                List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
            )
            customerIDMissing = arbitrarySample[CustomerID]

            _ = customerIDMissing shouldNot equal(customerBusinessDetailsRow.customerID)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerIDMissing,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
            _ = customerBookBusinessPutResponse.body.value shouldBe ()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "successfully leave the business and its contacts unchanged when the customer business is archived" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = true,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
            customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

            _ = customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate =
                List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
              customerBusinessContactsArchive =
                List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
            _ = customerBookBusinessPutResponse.body.value shouldBe ()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "successfully leave a customer individual targeted as a business unchanged" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
          customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

          _ = customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Replacement Business"),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
            customerBusinessContactsUpdate =
              List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
            customerBusinessContactsArchive =
              List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPutResponse <- gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookBusinessPutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully leave the business and its contacts unchanged when the customer business belongs to another organization" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = arbitrarySample[OrganizationID],
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )

            _ = customerBusinessDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
            customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

            _ = customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate =
                List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
              customerBusinessContactsArchive =
                List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
            _ = customerBookBusinessPutResponse.body.value shouldBe ()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "successfully ignore archive references to a missing or already archived contact without touching another business's contact with the same ID" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessDetailsRowOther = customerBusinessDetailsRow.copy(
              customerID = arbitrarySample[CustomerID],
              businessName = CustomerBusinessName.assume("Other Business"),
            )
            customerBusinessContactRowArchived = customerBusinessContactRow.copy(isArchived = true)
            customerBusinessContactRowOther    = customerBusinessContactRow.copy(customerID =
              customerBusinessDetailsRowOther.customerID
            )

            _ = customerBusinessDetailsRowOther.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

            customerBusinessContactIDMissing = arbitrarySample[CustomerBusinessContactID]

            _ = customerBusinessContactIDMissing shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessDetailsRows(
                  List(customerBusinessDetailsRow, customerBusinessDetailsRowOther)
                )
              )
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(
                  List(customerBusinessContactRowArchived, customerBusinessContactRowOther)
                )
              )

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              customerBusinessContactsArchive = List(
                smithy.CustomerBusinessContactArchive(
                  customerBusinessContactRowArchived.customerBusinessContactID.value
                ),
                smithy.CustomerBusinessContactArchive(customerBusinessContactIDMissing.value),
              )
            )
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
            _ = customerBookBusinessPutResponse.body.value shouldBe ()

            customerBusinessDetailsRows <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )

            customerBusinessDetailsRowUpdated = customerBusinessDetailsRows
              .find(_.customerID == customerBusinessDetailsRow.customerID)
              .value

            _ = customerBusinessDetailsRows should contain theSameElementsAs List(
              customerBusinessDetailsRow.copy(updatedAt = customerBusinessDetailsRowUpdated.updatedAt),
              customerBusinessDetailsRowOther,
            )
            _ = customerBusinessDetailsRowUpdated.updatedAt.value.isBefore(instantNow) shouldBe false

            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessContactRowsAll should contain theSameElementsAs List(
              customerBusinessContactRowArchived,
              customerBusinessContactRowOther,
            )
          } yield ()
      }

      "fail with a ValidationError for invalid nested contact fields without any writes" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Replacement Business"),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("")),
            customerBusinessContactsUpdate = List(
              smithy.CustomerBusinessContactUpdate(
                customerBusinessContactRow.customerBusinessContactID.value,
                role = Some(Nullable.Value("")),
              )
            ),
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessPutResponse <- gatewayClient
            .customerBookBusinessPut[smithy.ValidationError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessPutResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookBusinessPutResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("customerBusinessContactInsert", "customerBusinessContactUpdate")
          )

          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "fail with a ValidationError for duplicate and overlapping contact IDs without any writes" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            customerBusinessContactUpdateSmithy = smithy.CustomerBusinessContactUpdate(
              customerBusinessContactRow.customerBusinessContactID.value
            )
            customerBusinessContactArchiveSmithy = smithy.CustomerBusinessContactArchive(
              customerBusinessContactRow.customerBusinessContactID.value
            )
            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate =
                List(customerBusinessContactUpdateSmithy, customerBusinessContactUpdateSmithy),
              customerBusinessContactsArchive =
                List(customerBusinessContactArchiveSmithy, customerBusinessContactArchiveSmithy),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.ValidationError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.BadRequest
            _ = customerBookBusinessPutResponse.body.left.value shouldBe smithy.ValidationError(fields =
              List(
                "customerBusinessContactsUpdate",
                "customerBusinessContactsArchive",
                "customerBusinessContactsArchive",
                "customerBusinessContactsArchive",
              )
            )

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "fail with a Conflict on an active business name and roll back requested contact changes" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessDetailsRowExisting = customerBusinessDetailsRow.copy(
              customerID = arbitrarySample[CustomerID],
              businessName = CustomerBusinessName.assume("Taken Business"),
            )

            _ = customerBusinessDetailsRowExisting.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessDetailsRows(
                  List(customerBusinessDetailsRow, customerBusinessDetailsRowExisting)
                )
              )
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Taken Business"),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                )
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.Conflict](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.Conflict
            _ = customerBookBusinessPutResponse.body.left.value shouldBe smithy.Conflict(
              message = "A customer with the given name already exists in this organization"
            )

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll should contain theSameElementsAs List(
              customerBusinessDetailsRow,
              customerBusinessDetailsRowExisting,
            )
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "fail with an InternalServerError and roll back business and contacts when an update reference is missing" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessContactIDInvalid = arbitrarySample[CustomerBusinessContactID]

            _ = customerBusinessContactIDInvalid shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                ),
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactIDInvalid.value,
                  fullName = Some("Invalid Update"),
                ),
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "fail with an InternalServerError and roll back business and contacts when an update reference is archived" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessContactRowInvalid = customerBusinessContactRow.copy(
              customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
              isArchived = true,
            )

            _ = customerBusinessContactRowInvalid.customerBusinessContactID shouldNot equal(
              customerBusinessContactRow.customerBusinessContactID
            )

            customerBusinessContactIDInvalid = customerBusinessContactRowInvalid.customerBusinessContactID

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(
                  List(customerBusinessContactRow, customerBusinessContactRowInvalid)
                )
              )

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                ),
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactIDInvalid.value,
                  fullName = Some("Invalid Update"),
                ),
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll should contain theSameElementsAs List(
              customerBusinessContactRow,
              customerBusinessContactRowInvalid,
            )
          } yield ()
      }

      "fail with an InternalServerError and roll back business and contacts when an update reference is another business" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = CustomerBusinessName.assume("Original Business"),
              taxID = Some(CustomerTaxID.assume("OriginalTax")),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
              phoneNumbers =
                List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
              addresses =
                List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(CustomerEmail.assume("contact@example.com")),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessDetailsRowOther = customerBusinessDetailsRow.copy(
              customerID = arbitrarySample[CustomerID],
              businessName = CustomerBusinessName.assume("Other Business"),
            )
            customerBusinessContactRowInvalid = customerBusinessContactRow.copy(
              customerID = customerBusinessDetailsRowOther.customerID,
              customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
            )

            _ = customerBusinessDetailsRowOther.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

            _ = customerBusinessContactRowInvalid.customerBusinessContactID shouldNot equal(
              customerBusinessContactRow.customerBusinessContactID
            )

            customerBusinessContactIDInvalid = customerBusinessContactRowInvalid.customerBusinessContactID

            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessDetailsRows(
                  List(customerBusinessDetailsRow, customerBusinessDetailsRowOther)
                )
              )
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(
                  List(customerBusinessContactRow, customerBusinessContactRowInvalid)
                )
              )

            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                ),
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactIDInvalid.value,
                  fullName = Some("Invalid Update"),
                ),
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessPutResponse <- gatewayClient
              .customerBookBusinessPut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll should contain theSameElementsAs List(
              customerBusinessDetailsRow,
              customerBusinessDetailsRowOther,
            )
            _ = customerBusinessContactRowsAll should contain theSameElementsAs List(
              customerBusinessContactRow,
              customerBusinessContactRowInvalid,
            )
          } yield ()
      }
    }

    "PUT /customer-book/{customerID}/archive" should {
      "successfully archive a customer individual" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationUserRow.organizationID, isArchived = false)

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookArchivePutResponse <- gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookArchivePutResponse.body.value shouldBe ()

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(
            customerIndividualDetailsRow.copy(
              isArchived = true,
              updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
            )
          )
          _ = customerIndividualDetailsRowsAll.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully archive a business while retaining active and archived contacts in full" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          customerBusinessContactRowActive = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )
          customerBusinessContactRowArchived = customerBusinessContactRowActive.copy(
            customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
            isArchived = true,
          )

          _ = customerBusinessContactRowArchived.customerBusinessContactID shouldNot equal(
            customerBusinessContactRowActive.customerBusinessContactID
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(
                List(customerBusinessContactRowActive, customerBusinessContactRowArchived)
              )
            )

          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookArchivePutResponse <- gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookArchivePutResponse.body.value shouldBe ()

          customerBusinessDetailsRows <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerBusinessDetailsRows shouldBe List(
            customerBusinessDetailsRow.copy(
              isArchived = true,
              updatedAt = customerBusinessDetailsRows.head.updatedAt,
            )
          )
          _ = customerBusinessDetailsRows.head should not equal customerBusinessDetailsRow
          _ = customerBusinessDetailsRows.head.updatedAt.value.isBefore(instantNow) shouldBe false
          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll should contain theSameElementsAs List(
            customerBusinessContactRowActive,
            customerBusinessContactRowArchived,
          )
        } yield ()
      }

      "successfully do nothing when the customer does not exist" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookArchivePutResponse <- gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookArchivePutResponse.body.value shouldBe ()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe Nil
          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully leave an already archived customer business and its contacts unchanged" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = true,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookArchivePutResponse <- gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
          _ = customerBookArchivePutResponse.body.value shouldBe ()

          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "successfully leave another organization's customer business and its contacts unchanged" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = arbitrarySample[OrganizationID],
              isArchived = false,
            )
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
            )

            _ = customerBusinessDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookArchivePutResponse <- gatewayClient
              .customerBookArchivePut[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
            _ = customerBookArchivePutResponse.body.value shouldBe ()

            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerIndividualDetailsRowsAll shouldBe Nil
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }
    }

    "GET /customer-book/individual/{customerID}" should {
      "successfully return the customer individual's details" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          addressWithoutLine2          = addressFull.copy(addressLine2 = None)
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            emails = List(
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true),
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = false),
            ),
            phoneNumbers = List(
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true),
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = false),
            ),
            addresses = List(
              CustomerAddressEntry(AddressEntry(addressFull, AddressType.Shipping)),
              CustomerAddressEntry(AddressEntry(addressWithoutLine2, AddressType.Billing)),
            ),
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualGetResponse <- gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookIndividualGetResponse.body.value shouldBe smithy.CustomerBookIndividualGetResponse(
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
                  customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
                  customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
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

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with an InternalServerError when the customer individual does not exist" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualGetResponse <- gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with an InternalServerError when the customer individual is archived" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          addressWithoutLine2          = addressFull.copy(addressLine2 = None)
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = true,
            emails = List(
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true),
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = false),
            ),
            phoneNumbers = List(
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true),
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = false),
            ),
            addresses = List(
              CustomerAddressEntry(AddressEntry(addressFull, AddressType.Shipping)),
              CustomerAddressEntry(AddressEntry(addressWithoutLine2, AddressType.Billing)),
            ),
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualGetResponse <- gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with an InternalServerError when the customer individual is a customer business" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookIndividualGetResponse <- gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "fail with an InternalServerError when the customer individual belongs to another organization" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(isArchived = false)

            _ = customerIndividualDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookIndividualGetResponse <- gatewayClient
              .customerBookIndividualGet[smithy.InternalServerError](
                customerIndividualDetailsRow.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe Nil
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }
    }

    "GET /customer-book/business/{customerID}" should {
      "successfully return complete business values and only active contacts in creation-time and identifier order" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            addressFull = Address(
              addressLine1 = Some(arbitrarySample[AddressLine1]),
              addressLine2 = Some(arbitrarySample[AddressLine2]),
              city = Some(arbitrarySample[City]),
              postalCode = Some(arbitrarySample[PostalCode]),
              country = Some(arbitrarySample[Country]),
            )
            addressWithoutLine2        = addressFull.copy(addressLine2 = None)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              taxID = Some(arbitrarySample[CustomerTaxID]),
              emails = List(
                CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true),
                CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = false),
              ),
              phoneNumbers = List(
                CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true),
                CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = false),
              ),
              addresses = List(
                CustomerAddressEntry(AddressEntry(addressFull, AddressType.Shipping)),
                CustomerAddressEntry(AddressEntry(addressWithoutLine2, AddressType.Billing)),
              ),
            )
            createdAtEarlierText                = "2026-01-01T10:00:00Z"
            createdAtLaterText                  = "2026-01-02T10:00:00Z"
            customerBusinessContactIDFirstText  = "00000000-0000-0000-0000-000000000001"
            customerBusinessContactIDSecondText = "00000000-0000-0000-0000-000000000002"
            createdAtEarlier                    = CreatedAt(java.time.Instant.parse(createdAtEarlierText))
            createdAtLater                      = CreatedAt(java.time.Instant.parse(createdAtLaterText))
            customerBusinessContactIDFirst      = CustomerBusinessContactID(
              java.util.UUID.fromString(customerBusinessContactIDFirstText)
            )
            customerBusinessContactIDSecond = CustomerBusinessContactID(
              java.util.UUID.fromString(customerBusinessContactIDSecondText)
            )
            customerBusinessContactRowEarlier = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              createdAt = createdAtEarlier,
              updatedAt = UpdatedAt(createdAtEarlier.value),
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(arbitrarySample[CustomerEmail]),
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessContactRowFirst = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              customerBusinessContactID = customerBusinessContactIDFirst,
              createdAt = createdAtLater,
              updatedAt = UpdatedAt(createdAtLater.value),
              role = None,
              email = None,
              phoneNumber = None,
              isArchived = false,
            )
            customerBusinessContactRowSecond = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              customerBusinessContactID = customerBusinessContactIDSecond,
              createdAt = createdAtLater,
              updatedAt = UpdatedAt(createdAtLater.value),
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = None,
              phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
              isArchived = false,
            )
            customerBusinessContactRowArchived = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = customerBusinessDetailsRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              createdAt = createdAtEarlier,
              updatedAt = UpdatedAt(createdAtEarlier.value),
              isArchived = true,
            )
            customerBusinessContactCount = 4

            _ = Set(
              customerBusinessContactRowEarlier.customerBusinessContactID,
              customerBusinessContactRowFirst.customerBusinessContactID,
              customerBusinessContactRowSecond.customerBusinessContactID,
              customerBusinessContactRowArchived.customerBusinessContactID,
            ).size shouldBe customerBusinessContactCount

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowSecond))
              )
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowArchived))
              )
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowFirst))
              )
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowEarlier))
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessGetResponse <- gatewayClient
              .customerBookBusinessGet[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessGetResponse.code shouldBe StatusCode.Ok
            _ = customerBookBusinessGetResponse.body.value shouldBe smithy.CustomerBookBusinessGetResponse(
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
                  smithy.PhoneNumberRequest(
                    customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
                    customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
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
                customerBusinessContactRowEarlier,
                customerBusinessContactRowFirst,
                customerBusinessContactRowSecond,
              )
                .map(customerBusinessContactRow =>
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

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIndividualDetailsRowsAll shouldBe Nil
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll should contain theSameElementsAs List(
              customerBusinessContactRowSecond,
              customerBusinessContactRowArchived,
              customerBusinessContactRowFirst,
              customerBusinessContactRowEarlier,
            )
          } yield ()
      }

      "successfully return a required empty contact list for a business with no contacts" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          addressWithoutLine2        = addressFull.copy(addressLine2 = None)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            taxID = Some(arbitrarySample[CustomerTaxID]),
            emails = List(
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true),
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = false),
            ),
            phoneNumbers = List(
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true),
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = false),
            ),
            addresses = List(
              CustomerAddressEntry(AddressEntry(addressFull, AddressType.Shipping)),
              CustomerAddressEntry(AddressEntry(addressWithoutLine2, AddressType.Billing)),
            ),
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessGetResponse <- gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookBusinessGetResponse.body.value shouldBe smithy.CustomerBookBusinessGetResponse(
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
                smithy.PhoneNumberRequest(
                  customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
                  customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
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
            customerBusinessContacts = Nil,
          )

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully return a required empty contact list when all contacts are archived" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          addressWithoutLine2        = addressFull.copy(addressLine2 = None)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            taxID = Some(arbitrarySample[CustomerTaxID]),
            emails = List(
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true),
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = false),
            ),
            phoneNumbers = List(
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true),
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = false),
            ),
            addresses = List(
              CustomerAddressEntry(AddressEntry(addressFull, AddressType.Shipping)),
              CustomerAddressEntry(AddressEntry(addressWithoutLine2, AddressType.Billing)),
            ),
          )
          customerBusinessContactRowArchived = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = true,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowArchived))
            )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessGetResponse <- gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookBusinessGetResponse.body.value shouldBe smithy.CustomerBookBusinessGetResponse(
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
                smithy.PhoneNumberRequest(
                  customerPhoneNumberEntryInput.phoneNumber.value.phoneNationalNumber.value,
                  customerPhoneNumberEntryInput.phoneNumber.value.phoneCountryCode.value,
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
            customerBusinessContacts = Nil,
          )

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll should contain theSameElementsAs List(customerBusinessContactRowArchived)
        } yield ()
      }

      "fail with an InternalServerError when the customer business does not exist" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessGetResponse <- gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with an InternalServerError when the customer business is archived" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          addressWithoutLine2        = addressFull.copy(addressLine2 = None)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = true,
            taxID = Some(arbitrarySample[CustomerTaxID]),
            emails = List(
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true),
              CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = false),
            ),
            phoneNumbers = List(
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true),
              CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = false),
            ),
            addresses = List(
              CustomerAddressEntry(AddressEntry(addressFull, AddressType.Shipping)),
              CustomerAddressEntry(AddressEntry(addressWithoutLine2, AddressType.Billing)),
            ),
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(arbitrarySample[CustomerEmail]),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessGetResponse <- gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll should contain theSameElementsAs List(customerBusinessContactRow)
        } yield ()
      }

      "fail with an InternalServerError when the customer business is a customer individual" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookBusinessGetResponse <- gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with an InternalServerError when the customer business belongs to another organization" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)

            _ = customerBusinessDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookBusinessGetResponse <- gatewayClient
              .customerBookBusinessGet[smithy.InternalServerError](
                customerBusinessDetailsRow.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIndividualDetailsRowsAll shouldBe Nil
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }
    }

    "GET /customer-book/summary" should {
      "successfully return every active customer sorted case-insensitively by name when no IDs are supplied" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(
                organizationID = organizationUserRow.organizationID,
                isArchived = false,
                fullName = CustomerFullName.assume("alpha individual"),
              )
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(
                organizationID = organizationUserRow.organizationID,
                isArchived = false,
                businessName = CustomerBusinessName.assume("Zeta Business"),
              )
            customerIndividualDetailsRowArchived = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationUserRow.organizationID, isArchived = true)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(
                customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowArchived)
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookSummaryGetResponse <- gatewayClient
              .customerBookSummaryGet[smithy.InternalServerError](
                Nil,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
            _ = customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(
              customers = List(
                smithy.CustomerSummary(
                  customerIndividualDetailsRow.customerID.value,
                  customerIndividualDetailsRow.fullName.value,
                  smithy.CustomerType.Individual,
                ),
                smithy.CustomerSummary(
                  customerBusinessDetailsRow.customerID.value,
                  customerBusinessDetailsRow.businessName.value,
                  smithy.CustomerType.Business,
                ),
              )
            )

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIndividualDetailsRowsAll should contain theSameElementsAs List(
              customerIndividualDetailsRow,
              customerIndividualDetailsRowArchived,
            )
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }

      "successfully return an empty summary when the customer book is empty" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookSummaryGetResponse <- gatewayClient
            .customerBookSummaryGet[smithy.InternalServerError](
              Nil,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(customers = Nil)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully return requested active customers of both kinds once in case-insensitive name order, skipping archived, foreign, unknown and unrequested IDs" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRole,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              fullName = CustomerFullName.assume("alpha individual"),
            )
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              businessName = CustomerBusinessName.assume("Beta Business"),
            )
            customerIndividualDetailsRowArchived = customerIndividualDetailsRow.copy(
              customerID = arbitrarySample[CustomerID],
              isArchived = true,
            )
            customerBusinessDetailsRowForeign = customerBusinessDetailsRow.copy(
              organizationID = arbitrarySample[OrganizationID],
              customerID = arbitrarySample[CustomerID],
            )
            customerBusinessDetailsRowUnrequested = customerBusinessDetailsRow.copy(
              customerID = arbitrarySample[CustomerID],
              businessName = CustomerBusinessName.assume("Aardvark Unrequested"),
            )
            customerIDUnknown             = arbitrarySample[CustomerID]
            customerIndividualDetailsRows = List(customerIndividualDetailsRowArchived, customerIndividualDetailsRow)
            customerBusinessDetailsRows   = List(
              customerBusinessDetailsRowUnrequested,
              customerBusinessDetailsRowForeign,
              customerBusinessDetailsRow,
            )
            uniqueCustomerIDsCountExpected = 6
            customerIDs                    = List(
              customerBusinessDetailsRow.customerID,
              customerIndividualDetailsRow.customerID,
              customerIndividualDetailsRow.customerID,
              customerIndividualDetailsRowArchived.customerID,
              customerBusinessDetailsRowForeign.customerID,
              customerIDUnknown,
            )

            _ = customerBusinessDetailsRowForeign.organizationID shouldNot equal(
              customerBusinessDetailsRow.organizationID
            )

            _ = (customerIndividualDetailsRows.map(_.customerID) ++ customerBusinessDetailsRows.map(
              _.customerID
            ) :+ customerIDUnknown).distinct.size shouldBe uniqueCustomerIDsCountExpected

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookSummaryGetResponse <- gatewayClient
              .customerBookSummaryGet[smithy.InternalServerError](
                customerIDs,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
            _ = customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(
              customers = List(
                smithy.CustomerSummary(
                  customerIndividualDetailsRow.customerID.value,
                  customerIndividualDetailsRow.fullName.value,
                  smithy.CustomerType.Individual,
                ),
                smithy.CustomerSummary(
                  customerBusinessDetailsRow.customerID.value,
                  customerBusinessDetailsRow.businessName.value,
                  smithy.CustomerType.Business,
                ),
              )
            )

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIndividualDetailsRowsAll should contain theSameElementsAs customerIndividualDetailsRows
            _ = customerBusinessDetailsRowsAll should contain theSameElementsAs customerBusinessDetailsRows
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }

      "successfully return no matches instead of all customers for an unknown requested ID" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          customerIDUnknown = arbitrarySample[CustomerID]

          _ = customerIDUnknown shouldNot equal(customerIndividualDetailsRow.customerID)

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookSummaryGetResponse <- gatewayClient
            .customerBookSummaryGet[smithy.InternalServerError](
              List(customerIDUnknown),
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(customers = Nil)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }
    }

    "GET /customer-book/uniqueness" should {
      "successfully report an available name in an empty customer book without reserving it" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(customerID = None)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
            .CustomerBookUniquenessGetResponse(isNameUnique = true)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully report an active individual name as taken" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
            name = customerIndividualDetailsRow.fullName.to[CustomerName],
            customerType = CustomerType.Individual,
            customerID = None,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
            .CustomerBookUniquenessGetResponse(isNameUnique = false)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll should contain theSameElementsAs List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "successfully report an active business name as taken and retain its contacts" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
            name = customerBusinessDetailsRow.businessName.to[CustomerName],
            customerType = CustomerType.Business,
            customerID = None,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
            .CustomerBookUniquenessGetResponse(isNameUnique = false)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll should contain theSameElementsAs List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll should contain theSameElementsAs List(customerBusinessContactRow)
        } yield ()
      }

      "successfully report a name as unique when only another kind, an archived customer or another organization uses it" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
              customerType = CustomerType.Individual,
              customerID = None,
            )
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              businessName = customerBookUniquenessGetRequest.name.to[CustomerBusinessName],
              isArchived = false,
            )
            customerIndividualDetailsRowArchived = arbitrarySample[CustomerIndividualDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              fullName = customerBookUniquenessGetRequest.name.to[CustomerFullName],
              isArchived = true,
            )
            customerIndividualDetailsRowForeign = arbitrarySample[CustomerIndividualDetailsRow].copy(
              fullName = customerBookUniquenessGetRequest.name.to[CustomerFullName],
              isArchived = false,
            )
            customerIndividualDetailsRows = List(
              customerIndividualDetailsRowArchived,
              customerIndividualDetailsRowForeign,
            )

            _ = customerIndividualDetailsRowForeign.organizationID shouldNot equal(organizationUserRow.organizationID)

            _ = customerIndividualDetailsRowArchived.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookUniquenessGetResponse <- gatewayClient
              .customerBookUniquenessGet[smithy.InternalServerError](
                Some(customerBookUniquenessGetRequest.name.value),
                Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                customerBookUniquenessGetRequest.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
            _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
              .CustomerBookUniquenessGetResponse(isNameUnique = true)

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIndividualDetailsRowsAll should contain theSameElementsAs customerIndividualDetailsRows
            _ = customerBusinessDetailsRowsAll should contain theSameElementsAs List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }

      "successfully exclude the current active customer when checking its own name" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
            name = customerBusinessDetailsRow.businessName.to[CustomerName],
            customerType = CustomerType.Business,
            customerID = Some(customerBusinessDetailsRow.customerID),
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
            .CustomerBookUniquenessGetResponse(isNameUnique = true)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll should contain theSameElementsAs List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll should contain theSameElementsAs List(customerBusinessContactRow)
        } yield ()
      }

      "successfully report a matching active name as taken when the exclusion ID is unrelated" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
            )
            customerIDUnrelated              = arbitrarySample[CustomerID]
            customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
              name = customerIndividualDetailsRow.fullName.to[CustomerName],
              customerType = CustomerType.Individual,
              customerID = Some(customerIDUnrelated),
            )

            _ = customerIDUnrelated shouldNot equal(customerIndividualDetailsRow.customerID)

            _ <- postgresClient
              .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookUniquenessGetResponse <- gatewayClient
              .customerBookUniquenessGet[smithy.InternalServerError](
                Some(customerBookUniquenessGetRequest.name.value),
                Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                customerBookUniquenessGetRequest.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
            _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
              .CustomerBookUniquenessGetResponse(isNameUnique = false)

            customerIndividualDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            customerBusinessDetailsRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            customerBusinessContactRowsAll <- postgresClient
              .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

            _ = customerIndividualDetailsRowsAll should contain theSameElementsAs List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe Nil
            _ = customerBusinessContactRowsAll shouldBe Nil
          } yield ()
      }

      "successfully report a name as unique when only a differently cased name is taken" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBusinessNameRaw    = "Case Sensitive Name"
          customerNameRaw            = "case sensitive name"
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.either(customerBusinessNameRaw).toOption.value,
            isArchived = false,
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
            name = CustomerName.either(customerNameRaw).toOption.value,
            customerType = CustomerType.Business,
            customerID = None,
          )

          _ <- postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = customerBookUniquenessGetResponse.body.value shouldBe smithy
            .CustomerBookUniquenessGetResponse(isNameUnique = true)

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll should contain theSameElementsAs List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError naming both missing required query fields" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.ValidationError](
              None,
              None,
              None,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("name", "customerType")
          )

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError for an invalid supplied name" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]
          nameInvalid                      = ""

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookUniquenessGetResponse <- gatewayClient
            .customerBookUniquenessGet[smithy.ValidationError](
              Some(nameInvalid),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              None,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = customerBookUniquenessGetResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name"))

          customerIndividualDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          customerBusinessDetailsRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          customerBusinessContactRowsAll <- postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)

          _ = customerIndividualDetailsRowsAll shouldBe Nil
          _ = customerBusinessDetailsRowsAll shouldBe Nil
          _ = customerBusinessContactRowsAll shouldBe Nil
        } yield ()
      }
    }

    "middleware" should {
      "fail with a BadRequest when the organization id header is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationID               = arbitrarySample[OrganizationID]
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
          )

          customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
          customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
            .transformInto[smithy.CustomerBookBusinessPostRequest]
          customerBookBatchPostRequest = CustomerBookBatchPostRequest(
            customerBusinesses = List(
              arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
            ),
            customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
          )
          customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
            .transformInto[smithy.CustomerBookBatchPostRequest]
          customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
            fullName = Some("changed name"),
            emails = Some(Nil),
            phoneNumbers = Some(Nil),
            addresses = Some(Nil),
          )
          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Replacement Business"),
            taxID = Some(Nullable.Null),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
            customerBusinessContactsUpdate = List(
              smithy.CustomerBusinessContactUpdate(
                customerBusinessContactRow.customerBusinessContactID.value,
                fullName = Some("Updated Contact"),
              )
            ),
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.customerBookIndividualPost[smithy.BadRequest](
                customerBookIndividualPostRequestSmithy,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookBusinessPost[smithy.BadRequest](
                customerBookBusinessPostRequestSmithy,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookBatchPost[smithy.BadRequest](
                customerBookBatchPostRequestSmithy,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookIndividualPut[smithy.BadRequest](
                customerIndividualDetailsRow.customerID,
                customerBookIndividualPutRequestSmithy,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookBusinessPut[smithy.BadRequest](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookArchivePut[smithy.BadRequest](
                customerIndividualDetailsRow.customerID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookIndividualGet[smithy.BadRequest](
                customerIndividualDetailsRow.customerID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookBusinessGet[smithy.BadRequest](
                customerBusinessDetailsRow.customerID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookSummaryGet[smithy.BadRequest](
                Nil,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.customerBookUniquenessGet[smithy.BadRequest](
                Some(customerBookUniquenessGetRequest.name.value),
                Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                customerBookUniquenessGetRequest.customerID,
                None,
                Some(accessJwt.accessToken),
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(10)(StatusCode.BadRequest)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(10)(Some(smithy.BadRequest()))

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID               = arbitrarySample[OrganizationID]
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
          )

          customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
          customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
            .transformInto[smithy.CustomerBookBusinessPostRequest]
          customerBookBatchPostRequest = CustomerBookBatchPostRequest(
            customerBusinesses = List(
              arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
            ),
            customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
          )
          customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
            .transformInto[smithy.CustomerBookBatchPostRequest]
          customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
            fullName = Some("changed name"),
            emails = Some(Nil),
            phoneNumbers = Some(Nil),
            addresses = Some(Nil),
          )
          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Replacement Business"),
            taxID = Some(Nullable.Null),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
            customerBusinessContactsUpdate = List(
              smithy.CustomerBusinessContactUpdate(
                customerBusinessContactRow.customerBusinessContactID.value,
                fullName = Some("Updated Contact"),
              )
            ),
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.customerBookIndividualPost[smithy.Unauthorized](
                customerBookIndividualPostRequestSmithy,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookBusinessPost[smithy.Unauthorized](
                customerBookBusinessPostRequestSmithy,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookBatchPost[smithy.Unauthorized](
                customerBookBatchPostRequestSmithy,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookIndividualPut[smithy.Unauthorized](
                customerIndividualDetailsRow.customerID,
                customerBookIndividualPutRequestSmithy,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookBusinessPut[smithy.Unauthorized](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookArchivePut[smithy.Unauthorized](
                customerIndividualDetailsRow.customerID,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookIndividualGet[smithy.Unauthorized](
                customerIndividualDetailsRow.customerID,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookBusinessGet[smithy.Unauthorized](
                customerBusinessDetailsRow.customerID,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookSummaryGet[smithy.Unauthorized](
                Nil,
                Some(organizationID),
                None,
              ),
              gatewayClient.customerBookUniquenessGet[smithy.Unauthorized](
                Some(customerBookUniquenessGetRequest.name.value),
                Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                customerBookUniquenessGetRequest.customerID,
                Some(organizationID),
                None,
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(10)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(10)(Some(smithy.Unauthorized()))

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID               = arbitrarySample[OrganizationID]
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
          )

          customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
          customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
            .transformInto[smithy.CustomerBookBusinessPostRequest]
          customerBookBatchPostRequest = CustomerBookBatchPostRequest(
            customerBusinesses = List(
              arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
            ),
            customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
          )
          customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
            .transformInto[smithy.CustomerBookBatchPostRequest]
          customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
            fullName = Some("changed name"),
            emails = Some(Nil),
            phoneNumbers = Some(Nil),
            addresses = Some(Nil),
          )
          customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Replacement Business"),
            taxID = Some(Nullable.Null),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
            customerBusinessContactsUpdate = List(
              smithy.CustomerBusinessContactUpdate(
                customerBusinessContactRow.customerBusinessContactID.value,
                fullName = Some("Updated Contact"),
              )
            ),
          )
          customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.customerBookIndividualPost[smithy.Unauthorized](
                customerBookIndividualPostRequestSmithy,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookBusinessPost[smithy.Unauthorized](
                customerBookBusinessPostRequestSmithy,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookBatchPost[smithy.Unauthorized](
                customerBookBatchPostRequestSmithy,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookIndividualPut[smithy.Unauthorized](
                customerIndividualDetailsRow.customerID,
                customerBookIndividualPutRequestSmithy,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookBusinessPut[smithy.Unauthorized](
                customerBusinessDetailsRow.customerID,
                customerBookBusinessPutRequestSmithy,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookArchivePut[smithy.Unauthorized](
                customerIndividualDetailsRow.customerID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookIndividualGet[smithy.Unauthorized](
                customerIndividualDetailsRow.customerID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookBusinessGet[smithy.Unauthorized](
                customerBusinessDetailsRow.customerID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookSummaryGet[smithy.Unauthorized](
                Nil,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.customerBookUniquenessGet[smithy.Unauthorized](
                Some(customerBookUniquenessGetRequest.name.value),
                Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                customerBookUniquenessGetRequest.customerID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(10)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(10)(Some(smithy.Unauthorized()))

          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
        } yield ()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStageInvalid <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.completedStages)
              .map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalid)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationUserRow.organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationUserRow.organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationUserRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
              .transformInto[smithy.CustomerBookIndividualPostRequest]
            customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
              .transformInto[smithy.CustomerBookBusinessPostRequest]
            customerBookBatchPostRequest = CustomerBookBatchPostRequest(
              customerBusinesses = List(
                arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
              ),
              customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
            )
            customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
              .transformInto[smithy.CustomerBookBatchPostRequest]
            customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
              fullName = Some("changed name"),
              emails = Some(Nil),
              phoneNumbers = Some(Nil),
              addresses = Some(Nil),
            )
            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                )
              ),
            )
            customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookIndividualPost[smithy.Forbidden](
                  customerBookIndividualPostRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPost[smithy.Forbidden](
                  customerBookBusinessPostRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBatchPost[smithy.Forbidden](
                  customerBookBatchPostRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualPut[smithy.Forbidden](
                  customerIndividualDetailsRow.customerID,
                  customerBookIndividualPutRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPut[smithy.Forbidden](
                  customerBusinessDetailsRow.customerID,
                  customerBookBusinessPutRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookArchivePut[smithy.Forbidden](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualGet[smithy.Forbidden](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessGet[smithy.Forbidden](
                  customerBusinessDetailsRow.customerID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookSummaryGet[smithy.Forbidden](
                  Nil,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookUniquenessGet[smithy.Forbidden](
                  Some(customerBookUniquenessGetRequest.name.value),
                  Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                  customerBookUniquenessGetRequest.customerID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(10)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(10)(Some(smithy.Forbidden()))

            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "fail with a Forbidden when the organization user role is not allowed on every write endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRoleInvalid <- Random
              .shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles)
              .map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationUserRow.organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationUserRow.organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationUserRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
              .transformInto[smithy.CustomerBookIndividualPostRequest]
            customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
              .transformInto[smithy.CustomerBookBusinessPostRequest]
            customerBookBatchPostRequest = CustomerBookBatchPostRequest(
              customerBusinesses = List(
                arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
              ),
              customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
            )
            customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
              .transformInto[smithy.CustomerBookBatchPostRequest]
            customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
              fullName = Some("changed name"),
              emails = Some(Nil),
              phoneNumbers = Some(Nil),
              addresses = Some(Nil),
            )
            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                )
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookIndividualPost[smithy.Forbidden](
                  customerBookIndividualPostRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPost[smithy.Forbidden](
                  customerBookBusinessPostRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBatchPost[smithy.Forbidden](
                  customerBookBatchPostRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualPut[smithy.Forbidden](
                  customerIndividualDetailsRow.customerID,
                  customerBookIndividualPutRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPut[smithy.Forbidden](
                  customerBusinessDetailsRow.customerID,
                  customerBookBusinessPutRequestSmithy,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookArchivePut[smithy.Forbidden](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(6)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(6)(Some(smithy.Forbidden()))

            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            userID                       = arbitrarySample[UserID]
            organizationID               = arbitrarySample[OrganizationID]
            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
              .transformInto[smithy.CustomerBookIndividualPostRequest]
            customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
              .transformInto[smithy.CustomerBookBusinessPostRequest]
            customerBookBatchPostRequest = CustomerBookBatchPostRequest(
              customerBusinesses = List(
                arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
              ),
              customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
            )
            customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
              .transformInto[smithy.CustomerBookBatchPostRequest]
            customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
              fullName = Some("changed name"),
              emails = Some(Nil),
              phoneNumbers = Some(Nil),
              addresses = Some(Nil),
            )
            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                )
              ),
            )
            customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

            accessJwt <- jwtService.generateAccessToken(userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookIndividualPost[smithy.InternalServerError](
                  customerBookIndividualPostRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPost[smithy.InternalServerError](
                  customerBookBusinessPostRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBatchPost[smithy.InternalServerError](
                  customerBookBatchPostRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualPut[smithy.InternalServerError](
                  customerIndividualDetailsRow.customerID,
                  customerBookIndividualPutRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPut[smithy.InternalServerError](
                  customerBusinessDetailsRow.customerID,
                  customerBookBusinessPutRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookArchivePut[smithy.InternalServerError](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualGet[smithy.InternalServerError](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessGet[smithy.InternalServerError](
                  customerBusinessDetailsRow.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookSummaryGet[smithy.InternalServerError](
                  Nil,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookUniquenessGet[smithy.InternalServerError](
                  Some(customerBookUniquenessGetRequest.name.value),
                  Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                  customerBookUniquenessGetRequest.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(10)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(10)(Some(smithy.InternalServerError()))

            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }

      "fail with an InternalServerError when the user is not a member of the organization on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationID               = arbitrarySample[OrganizationID]
            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
              .transformInto[smithy.CustomerBookIndividualPostRequest]
            customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
              .transformInto[smithy.CustomerBookBusinessPostRequest]
            customerBookBatchPostRequest = CustomerBookBatchPostRequest(
              customerBusinesses = List(
                arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
              ),
              customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
            )
            customerBookBatchPostRequestSmithy = customerBookBatchPostRequest
              .transformInto[smithy.CustomerBookBatchPostRequest]
            customerBookIndividualPutRequestSmithy = smithy.CustomerBookIndividualPutRequest(
              fullName = Some("changed name"),
              emails = Some(Nil),
              phoneNumbers = Some(Nil),
              addresses = Some(Nil),
            )
            customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
              businessName = Some("Replacement Business"),
              taxID = Some(Nullable.Null),
              customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
              customerBusinessContactsUpdate = List(
                smithy.CustomerBusinessContactUpdate(
                  customerBusinessContactRow.customerBusinessContactID.value,
                  fullName = Some("Updated Contact"),
                )
              ),
            )
            customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookIndividualPost[smithy.InternalServerError](
                  customerBookIndividualPostRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPost[smithy.InternalServerError](
                  customerBookBusinessPostRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBatchPost[smithy.InternalServerError](
                  customerBookBatchPostRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualPut[smithy.InternalServerError](
                  customerIndividualDetailsRow.customerID,
                  customerBookIndividualPutRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessPut[smithy.InternalServerError](
                  customerBusinessDetailsRow.customerID,
                  customerBookBusinessPutRequestSmithy,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookArchivePut[smithy.InternalServerError](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookIndividualGet[smithy.InternalServerError](
                  customerIndividualDetailsRow.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookBusinessGet[smithy.InternalServerError](
                  customerBusinessDetailsRow.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookSummaryGet[smithy.InternalServerError](
                  Nil,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.customerBookUniquenessGet[smithy.InternalServerError](
                  Some(customerBookUniquenessGetRequest.name.value),
                  Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
                  customerBookUniquenessGetRequest.customerID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(10)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(10)(Some(smithy.InternalServerError()))

            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)
          } yield ()
      }
    }
  }
}
