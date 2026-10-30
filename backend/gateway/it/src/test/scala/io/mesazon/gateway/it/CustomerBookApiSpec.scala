package io.mesazon.gateway.it

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.it.client.GatewayClient
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

@DoNotDiscover
class CustomerBookApiSpec extends GatewayAcceptanceTest, CustomerBookSmithyArbitraries, RepositoryArbitraries {

  "Customer Book Service API" when {
    "POST /customer-book/individual" should {
      "successfully insert a customer individual" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.InternalServerError](
              customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.Ok

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue

        customerIndividualDetailsRowsAll should have size 1
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          .zioValue should have size 1
        customerIndividualDetailsRowsAll.head shouldBe CustomerIndividualDetailsRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerIndividualDetailsRowsAll.head.customerID,
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
        customerBookIndividualPostResponse.body.value shouldBe smithy.CustomerBookIndividualPostResponse(
          customerID = customerIndividualDetailsRowsAll.head.customerID.value
        )
      }

      "fail with a ValidationError when the full name is invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
            .copy(fullName = "")

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.ValidationError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.BadRequest
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("fullName"))

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.BadRequest](
              customerBookIndividualPostRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.BadRequest
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.Unauthorized](
              customerBookIndividualPostRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.Unauthorized](
              customerBookIndividualPostRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.Forbidden](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.Forbidden
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.Forbidden](
              customerBookIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.Forbidden
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when the full name already exists in the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]

        val customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            fullName = customerBookIndividualPostRequest.fullName,
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.Conflict](
              customerBookIndividualPostRequest.transformInto[smithy.CustomerBookIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.Conflict
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.Conflict(message =
          "A customer with the given name already exists in this organization"
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
          List(customerIndividualDetailsRowExisting.customerID)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRowExisting)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.InternalServerError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPostRequestSmithy =
          arbitrarySample[CustomerBookIndividualPostRequest].transformInto[smithy.CustomerBookIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPostResponse =
          gatewayClient
            .customerBookIndividualPost[smithy.InternalServerError](
              customerBookIndividualPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }
    }

    "POST /customer-book/business" should {
      "successfully insert a customer business with its inline contact" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessContactInsert =
          arbitrarySample[CustomerBusinessContactInsert]
        val customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
          .copy(customerBusinessContacts = List(customerBusinessContactInsert))

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.InternalServerError](
              customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

        val customerBusinessDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue

        customerBusinessDetailsRowsAll should have size 1
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          .zioValue should have size 1
        customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerBusinessDetailsRowsAll.head.customerID,
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

        val customerBusinessContactRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue

        customerBusinessContactRowsAll should have size customerBookBusinessPostRequest.customerBusinessContacts.size
        customerBusinessContactRowsAll.head shouldBe CustomerBusinessContactRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerBusinessDetailsRowsAll.head.customerID,
          customerBusinessContactID = customerBusinessContactRowsAll.head.customerBusinessContactID,
          fullName = customerBusinessContactInsert.fullName,
          role = customerBusinessContactInsert.role,
          email = customerBusinessContactInsert.email,
          phoneNumber = customerBusinessContactInsert.phoneNumber,
          createdAt = customerBusinessContactRowsAll.head.createdAt,
          updatedAt = customerBusinessContactRowsAll.head.updatedAt,
          isArchived = false,
        )

        customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
          customerID = customerBusinessDetailsRowsAll.head.customerID.value
        )
      }

      "successfully insert both inline contacts sharing the same email" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerEmail                  = arbitrarySample[CustomerEmail]
          val customerBusinessContactInsert1 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(fullName = CustomerFullName.assume("contact one"), email = Some(customerEmail), phoneNumber = None)
          val customerBusinessContactInsert2 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(fullName = CustomerFullName.assume("contact two"), email = Some(customerEmail), phoneNumber = None)
          val customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = List(customerBusinessContactInsert1, customerBusinessContactInsert2))

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBusinessPostResponse =
            gatewayClient
              .customerBookBusinessPost[smithy.InternalServerError](
                customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )
              .zioValue

          customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

          val customerBusinessDetailsRowsAll =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue
          val customerBusinessContactRowsAll =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue

          customerBusinessDetailsRowsAll should have size 1
          customerBusinessContactRowsAll should have size customerBookBusinessPostRequest.customerBusinessContacts.size
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List(
            customerBusinessDetailsRowsAll.head.customerID
          )
          customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = customerBusinessDetailsRowsAll.head.customerID,
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
          customerBusinessContactRowsAll should contain theSameElementsAs customerBookBusinessPostRequest.customerBusinessContacts.map {
            customerBusinessContactInsert =>
              val customerBusinessContactRow =
                customerBusinessContactRowsAll.find(_.fullName == customerBusinessContactInsert.fullName).value

              CustomerBusinessContactRow(
                organizationID = organizationUserRow.organizationID,
                customerID = customerBusinessDetailsRowsAll.head.customerID,
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

        customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
          customerID = customerBusinessDetailsRowsAll.head.customerID.value
        )
      }

      "successfully insert both inline contacts sharing the same phone number" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerPhoneNumber            = arbitrarySample[CustomerPhoneNumber]
          val customerBusinessContactInsert1 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(
              fullName = CustomerFullName.assume("contact one"),
              email = None,
              phoneNumber = Some(customerPhoneNumber),
            )
          val customerBusinessContactInsert2 = arbitrarySample[CustomerBusinessContactInsert]
            .copy(
              fullName = CustomerFullName.assume("contact two"),
              email = None,
              phoneNumber = Some(customerPhoneNumber),
            )
          val customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = List(customerBusinessContactInsert1, customerBusinessContactInsert2))

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBusinessPostResponse =
            gatewayClient
              .customerBookBusinessPost[smithy.InternalServerError](
                customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )
              .zioValue

          customerBookBusinessPostResponse.code shouldBe StatusCode.Ok

          val customerBusinessDetailsRowsAll =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue
          val customerBusinessContactRowsAll =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue

          customerBusinessDetailsRowsAll should have size 1
          customerBusinessContactRowsAll should have size customerBookBusinessPostRequest.customerBusinessContacts.size
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List(
            customerBusinessDetailsRowsAll.head.customerID
          )
          customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
            organizationID = organizationUserRow.organizationID,
            customerID = customerBusinessDetailsRowsAll.head.customerID,
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
          customerBusinessContactRowsAll should contain theSameElementsAs customerBookBusinessPostRequest.customerBusinessContacts.map {
            customerBusinessContactInsert =>
              val customerBusinessContactRow =
                customerBusinessContactRowsAll.find(_.fullName == customerBusinessContactInsert.fullName).value

              CustomerBusinessContactRow(
                organizationID = organizationUserRow.organizationID,
                customerID = customerBusinessDetailsRowsAll.head.customerID,
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

        customerBookBusinessPostResponse.body.value shouldBe smithy.CustomerBookBusinessPostResponse(
          customerID = customerBusinessDetailsRowsAll.head.customerID.value
        )
      }

      "fail with a ValidationError when the business name is invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookBusinessPostRequestSmithy =
          arbitrarySample[CustomerBookBusinessPostRequest]
            .transformInto[smithy.CustomerBookBusinessPostRequest]
            .copy(businessName = "")

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.ValidationError](
              customerBookBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.BadRequest
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("businessName"))

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.BadRequest](
              customerBookBusinessPostRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.BadRequest
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.Unauthorized](
              customerBookBusinessPostRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.Unauthorized](
              customerBookBusinessPostRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.Forbidden](
              customerBookBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.Forbidden
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.Forbidden](
              customerBookBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.Forbidden
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when the business name already exists in the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookBusinessPostRequest = arbitrarySample[CustomerBookBusinessPostRequest]
          .copy(customerBusinessContacts = List.empty)

        val customerBusinessDetailsRowExisting = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            businessName = customerBookBusinessPostRequest.businessName,
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRowExisting))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.Conflict](
              customerBookBusinessPostRequest.transformInto[smithy.CustomerBookBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.Conflict
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.Conflict(message =
          "A customer with the given name already exists in this organization"
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
          List(customerBusinessDetailsRowExisting.customerID)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRowExisting)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.InternalServerError](
              customerBookBusinessPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
          .transformInto[smithy.CustomerBookBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPostResponse =
          gatewayClient
            .customerBookBusinessPost[smithy.InternalServerError](
              customerBookBusinessPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }
    }

    "POST /customer-book/batch" should {
      "successfully insert individuals before businesses in the response and persist every customer and inline contact" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerFullName1             = CustomerFullName.assume("Zeta Individual")
          val customerFullName2             = CustomerFullName.assume("Alpha Individual")
          val customerBusinessName1         = CustomerBusinessName.assume("Zeta Business")
          val customerBusinessName2         = CustomerBusinessName.assume("Alpha Business")
          val customerBusinessContactInsert =
            arbitrarySample[CustomerBusinessContactInsert]
          val customerIndividuals = List(
            arbitrarySample[CustomerBookIndividualPostRequest]
              .copy(fullName = customerFullName1),
            arbitrarySample[CustomerBookIndividualPostRequest]
              .copy(fullName = customerFullName2),
          )
          val customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest]
              .copy(businessName = customerBusinessName1)
              .copy(customerBusinessContacts = List(customerBusinessContactInsert)),
            arbitrarySample[CustomerBookBusinessPostRequest]
              .copy(businessName = customerBusinessName2, customerBusinessContacts = List.empty),
          )
          val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
            customerBusinesses = customerBusinesses,
            customerIndividuals = customerIndividuals,
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBatchPostResponse = gatewayClient
            .customerBookBatchPost[smithy.InternalServerError](
              customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBatchPostResponse.code shouldBe StatusCode.Ok

          val customerIndividualDetailsRows =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue
          val customerBusinessDetailsRows =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue
          val customerBusinessContactRows =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue

          customerIndividualDetailsRows should have size customerIndividuals.size
          customerBusinessDetailsRows should have size customerBusinesses.size
          customerBusinessContactRows should have size customerBusinesses.flatMap(_.customerBusinessContacts).size
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            .zioValue should have size (customerIndividuals.size + customerBusinesses.size)

          customerIndividuals.foreach { customerBookIndividualPostRequest =>
            val customerIndividualDetailsRow =
              customerIndividualDetailsRows.find(_.fullName == customerBookIndividualPostRequest.fullName).value
            customerIndividualDetailsRow shouldBe CustomerIndividualDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerIndividualDetailsRow.customerID,
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
              createdAt = customerIndividualDetailsRow.createdAt,
              updatedAt = customerIndividualDetailsRow.updatedAt,
            )
          }
          customerBusinesses.foreach { customerBookBusinessPostRequest =>
            val customerBusinessDetailsRow =
              customerBusinessDetailsRows.find(_.businessName == customerBookBusinessPostRequest.businessName).value
            customerBusinessDetailsRow shouldBe CustomerBusinessDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerBusinessDetailsRow.customerID,
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
              createdAt = customerBusinessDetailsRow.createdAt,
              updatedAt = customerBusinessDetailsRow.updatedAt,
            )
          }

          val customerBusinessDetailsRowFirst =
            customerBusinessDetailsRows.find(_.businessName == customerBusinessName1).value
          customerBusinessContactRows.head shouldBe CustomerBusinessContactRow(
            organizationID = organizationUserRow.organizationID,
            customerID = customerBusinessDetailsRowFirst.customerID,
            customerBusinessContactID = customerBusinessContactRows.head.customerBusinessContactID,
            fullName = customerBusinessContactInsert.fullName,
            role = customerBusinessContactInsert.role,
            email = customerBusinessContactInsert.email,
            phoneNumber = customerBusinessContactInsert.phoneNumber,
            createdAt = customerBusinessContactRows.head.createdAt,
            updatedAt = customerBusinessContactRows.head.updatedAt,
            isArchived = false,
          )

          customerBookBatchPostResponse.body.value shouldBe smithy.CustomerBookBatchPostResponse(
            customerIDs = customerIndividuals.map(customerBookIndividualPostRequest =>
              customerIndividualDetailsRows
                .find(_.fullName == customerBookIndividualPostRequest.fullName)
                .value
                .customerID
                .value
            ) ++ customerBusinesses.map(customerBookBusinessPostRequest =>
              customerBusinessDetailsRows
                .find(_.businessName == customerBookBusinessPostRequest.businessName)
                .value
                .customerID
                .value
            )
          )
      }

      "successfully insert an empty batch without persisting customers or contacts" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val customerBookBatchPostRequestSmithy = smithy.CustomerBookBatchPostRequest(
          customerBusinesses = Nil,
          customerIndividuals = Nil,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.InternalServerError](
            customerBookBatchPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.Ok
        customerBookBatchPostResponse.body.value shouldBe smithy.CustomerBookBatchPostResponse(customerIDs = Nil)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil
        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError for invalid businesses and individuals without persisting any customer" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBookBusinessPostRequestSmithy = arbitrarySample[CustomerBookBusinessPostRequest]
            .copy(customerBusinessContacts = Nil)
            .transformInto[smithy.CustomerBookBusinessPostRequest]
          val customerBookIndividualPostRequestSmithy = arbitrarySample[CustomerBookIndividualPostRequest]
            .transformInto[smithy.CustomerBookIndividualPostRequest]
          val customerBookBatchPostRequestSmithy = smithy.CustomerBookBatchPostRequest(
            customerBusinesses = List(customerBookBusinessPostRequestSmithy.copy(businessName = "")),
            customerIndividuals = List(customerBookIndividualPostRequestSmithy.copy(fullName = "")),
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBatchPostResponse = gatewayClient
            .customerBookBatchPost[smithy.ValidationError](
              customerBookBatchPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBatchPostResponse.code shouldBe StatusCode.BadRequest
          customerBookBatchPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("customerBusiness", "customerIndividual")
          )
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.BadRequest](
            customerBookBatchPostRequestSmithy,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.BadRequest
        customerBookBatchPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.Unauthorized](
            customerBookBatchPostRequestSmithy,
            Some(arbitrarySample[OrganizationID]),
            None,
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookBatchPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.Unauthorized](
            customerBookBatchPostRequestSmithy,
            Some(arbitrarySample[OrganizationID]),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookBatchPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val onboardStage   = Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.Forbidden](
            customerBookBatchPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.Forbidden
        customerBookBatchPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.Forbidden](
            customerBookBatchPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.Forbidden
        customerBookBatchPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict for an existing individual name and leave the accompanying business unpersisted" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting))
            .zioValue
          val customerBusinessContactInsert =
            arbitrarySample[CustomerBusinessContactInsert]
          val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
            customerBusinesses = List(
              arbitrarySample[CustomerBookBusinessPostRequest]
                .copy(customerBusinessContacts = List(customerBusinessContactInsert))
            ),
            customerIndividuals = List(
              arbitrarySample[CustomerBookIndividualPostRequest]
                .copy(fullName = customerIndividualDetailsRowExisting.fullName)
            ),
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBatchPostResponse = gatewayClient
            .customerBookBatchPost[smithy.Conflict](
              customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBatchPostResponse.code shouldBe StatusCode.Conflict
          customerBookBatchPostResponse.body.left.value shouldBe smithy.Conflict(
            message = "A customer with the given name already exists in this organization"
          )
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
            List(customerIndividualDetailsRowExisting.customerID)
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe Nil
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List(customerIndividualDetailsRowExisting)
      }

      "fail with a Conflict for an existing business name and roll back the accompanying individual" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRowExisting = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRowExisting))
            .zioValue
          val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
            customerBusinesses = List(
              arbitrarySample[CustomerBookBusinessPostRequest]
                .copy(
                  businessName = customerBusinessDetailsRowExisting.businessName,
                  customerBusinessContacts = List.empty,
                )
            ),
            customerIndividuals = List(
              arbitrarySample[CustomerBookIndividualPostRequest]
            ),
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBatchPostResponse = gatewayClient
            .customerBookBatchPost[smithy.Conflict](
              customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBatchPostResponse.code shouldBe StatusCode.Conflict
          customerBookBatchPostResponse.body.left.value shouldBe smithy.Conflict(
            message = "A customer with the given name already exists in this organization"
          )
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe
            List(customerBusinessDetailsRowExisting.customerID)
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRowExisting)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]
        val accessJwt      = jwtService.generateAccessToken(userID).zioValue

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.InternalServerError](
            customerBookBatchPostRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookBatchPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val customerBookBatchPostRequest = CustomerBookBatchPostRequest(
          customerBusinesses = List(
            arbitrarySample[CustomerBookBusinessPostRequest].copy(customerBusinessContacts = Nil)
          ),
          customerIndividuals = List(arbitrarySample[CustomerBookIndividualPostRequest]),
        )
        val customerBookBatchPostRequestSmithy =
          customerBookBatchPostRequest.transformInto[smithy.CustomerBookBatchPostRequest]

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBatchPostResponse = gatewayClient
          .customerBookBatchPost[smithy.InternalServerError](
            customerBookBatchPostRequestSmithy,
            Some(arbitrarySample[OrganizationID]),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBatchPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookBatchPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }
    }

    "PUT /customer-book/individual/{customerID}" should {
      "successfully replace the supplied name and contact lists" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          )
        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]
          .copy(
            emails = List(CustomerEmailEntry(arbitrarySample[CustomerEmail], isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntry(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerBookIndividualPutRequestSmithy = CustomerBookIndividualPutRequest(
          fullName = Some(customerBookIndividualPostRequest.fullName),
          emails = Some(customerBookIndividualPostRequest.emails),
          phoneNumbers = Some(customerBookIndividualPostRequest.phoneNumbers),
          addresses = Some(customerBookIndividualPostRequest.addresses),
        ).transformInto[smithy.CustomerBookIndividualPutRequest]
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
          .customerBookIndividualPut[smithy.InternalServerError](
            customerIndividualDetailsRow.customerID,
            customerBookIndividualPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue

        customerIndividualDetailsRowsAll shouldBe List(
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
        customerIndividualDetailsRowsAll.head should not equal customerIndividualDetailsRow
        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List(
          customerIndividualDetailsRow.customerID
        )
      }

      "successfully retain omitted lists when updating the name" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          )
        val customerBookIndividualPostRequest = arbitrarySample[CustomerBookIndividualPostRequest]

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest(fullName = Some(customerBookIndividualPostRequest.fullName.value))
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
          .customerBookIndividualPut[smithy.InternalServerError](
            customerIndividualDetailsRow.customerID,
            customerBookIndividualPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue

        customerIndividualDetailsRowsAll shouldBe List(
          customerIndividualDetailsRow.copy(
            fullName = customerBookIndividualPostRequest.fullName,
            updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
          )
        )
        customerIndividualDetailsRowsAll.head should not equal customerIndividualDetailsRow
        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List(
          customerIndividualDetailsRow.customerID
        )
      }

      "successfully clear explicitly empty lists while retaining the omitted name" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            emails = List(CustomerEmailEntryInput(arbitrarySample[CustomerEmail], isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest(emails = Some(Nil), phoneNumbers = Some(Nil), addresses = Some(Nil))
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
          .customerBookIndividualPut[smithy.InternalServerError](
            customerIndividualDetailsRow.customerID,
            customerBookIndividualPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue

        customerIndividualDetailsRowsAll shouldBe List(
          customerIndividualDetailsRow.copy(
            emails = Nil,
            phoneNumbers = Nil,
            addresses = Nil,
            updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
          )
        )
        customerIndividualDetailsRowsAll.head should not equal customerIndividualDetailsRow
        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List(
          customerIndividualDetailsRow.customerID
        )
      }

      "successfully do nothing when the customer is missing" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
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
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "successfully retain an archived customer unchanged" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(organizationID = organizationUserRow.organizationID, isArchived = true)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
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
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)
      }

      "successfully retain another organization customer unchanged" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = false)

        customerIndividualDetailsRow.organizationID should not be organizationUserRow.organizationID

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
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
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)
      }

      "successfully retain a business addressed as an individual unchanged" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(organizationID = organizationUserRow.organizationID, isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse = gatewayClient
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
          .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.NoContent
        customerBookIndividualPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil
      }

      "fail with a ValidationError when the full name is invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest(fullName = Some(""))

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.ValidationError](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.BadRequest
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.ValidationError(fields = List("fullName"))

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.BadRequest](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.BadRequest
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.Unauthorized
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.Unauthorized
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.Forbidden](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.Forbidden
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.Forbidden](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.Forbidden
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when renaming to an active individual name and retain both complete rows" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              fullName = CustomerFullName.assume("first customer"),
            )
          val customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
              fullName = CustomerFullName.assume("second customer"),
            )

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            .zioValue
          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookIndividualPutResponse = gatewayClient
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
            .zioValue

          customerBookIndividualPutResponse.code shouldBe StatusCode.Conflict
          customerBookIndividualPutResponse.body.left.value shouldBe smithy.Conflict(message =
            "A customer with the given name already exists in this organization"
          )

          val customerIndividualDetailsRowsAll =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue

          customerIndividualDetailsRowsAll should contain theSameElementsAs List(
            customerIndividualDetailsRow,
            customerIndividualDetailsRowExisting,
          )
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val customerBookIndividualPutRequestSmithy =
          smithy.CustomerBookIndividualPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualPutResponse =
          gatewayClient
            .customerBookIndividualPut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              customerBookIndividualPutRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualPutResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil
      }
    }

    "PUT /customer-book/business/{customerID}" should {
      "successfully replace business fields and atomically insert update and archive contacts while retaining unnamed contacts" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
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

          val customerBusinessContactRowArchive =
            customerBusinessContactRow.copy(customerBusinessContactID = arbitrarySample[CustomerBusinessContactID])
          val customerBusinessContactRowRetained =
            customerBusinessContactRow.copy(customerBusinessContactID = arbitrarySample[CustomerBusinessContactID])
          val customerBusinessContactRowAlreadyArchived = customerBusinessContactRow.copy(
            customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
            isArchived = true,
          )
          val customerBusinessContactRows = List(
            customerBusinessContactRow,
            customerBusinessContactRowArchive,
            customerBusinessContactRowRetained,
            customerBusinessContactRowAlreadyArchived,
          )

          customerBusinessContactRows
            .map(_.customerBusinessContactID)
            .distinct
            .size shouldBe customerBusinessContactRows.size

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(customerBusinessContactRows))
            .zioValue

          val customerEmailReplacement       = CustomerEmail.assume("shared-replacement@example.com")
          val customerPhoneNumberReplacement = CustomerPhoneNumber(
            PhoneNumber(
              PhoneRegion.assume("GB"),
              PhoneCountryCode.assume("+44"),
              PhoneNationalNumber.assume("7754767565"),
              PhoneNumberE164.assume("+447754767565"),
            )
          )
          val customerBusinessContactInsert = CustomerBusinessContactInsert(
            CustomerFullName.assume("Inserted Contact"),
            customerBusinessContactRow.role,
            Some(customerEmailReplacement),
            Some(customerPhoneNumberReplacement),
          )
          val customerBookBusinessPutRequest = CustomerBookBusinessPutRequest(
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
          val customerBookBusinessPutRequestSmithy =
            customerBookBusinessPutRequest.transformInto[smithy.CustomerBookBusinessPutRequest]
          val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookBusinessPutResponse = gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
          customerBookBusinessPutResponse.body.value shouldBe ()

          val customerBusinessDetailsRows =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue
          val customerBusinessDetailsRowUpdated = customerBusinessDetailsRows.head

          customerBusinessDetailsRows shouldBe List(
            customerBusinessDetailsRow.copy(
              businessName = CustomerBusinessName.assume("Replacement Business"),
              emails = List(CustomerEmailEntryInput(CustomerEmail.assume("replacement@example.com"), isDefault = true)),
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
          customerBusinessDetailsRowUpdated should not equal customerBusinessDetailsRow

          val customerBusinessContactRowsPersisted =
            postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue
          val customerBusinessContactRowInserted =
            customerBusinessContactRowsPersisted.find(_.fullName == customerBusinessContactInsert.fullName).value
          val customerBusinessContactRowUpdated = customerBusinessContactRowsPersisted
            .find(
              _.customerBusinessContactID == customerBusinessContactRow.customerBusinessContactID
            )
            .value

          customerBusinessContactRowUpdated.email shouldNot equal(customerBusinessContactRow.email)
          customerBusinessContactRowUpdated.phoneNumber shouldNot equal(customerBusinessContactRow.phoneNumber)
          customerBusinessContactRowInserted.email shouldBe customerBusinessContactRowUpdated.email
          customerBusinessContactRowInserted.phoneNumber shouldBe customerBusinessContactRowUpdated.phoneNumber
          customerBusinessContactRowsPersisted should contain theSameElementsAs List(
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
      }

      "successfully keep omitted fields and default empty contact actions" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest()
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse      = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        val customerBusinessDetailsRows =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue

        customerBusinessDetailsRows shouldBe List(
          customerBusinessDetailsRow.copy(updatedAt = customerBusinessDetailsRows.head.updatedAt)
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "successfully clear supplied empty customer lists and nullable tax and contact fields" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
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
        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        val customerBusinessDetailsRows =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue

        customerBusinessDetailsRows shouldBe List(
          customerBusinessDetailsRow.copy(
            taxID = None,
            emails = Nil,
            phoneNumbers = Nil,
            addresses = Nil,
            updatedAt = customerBusinessDetailsRows.head.updatedAt,
          )
        )
        customerBusinessDetailsRows.head should not equal customerBusinessDetailsRow

        val customerBusinessContactRows =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue

        customerBusinessContactRows shouldBe List(
          customerBusinessContactRow.copy(
            role = None,
            email = None,
            phoneNumber = None,
            updatedAt = customerBusinessDetailsRows.head.updatedAt,
          )
        )
        customerBusinessContactRows.head should not equal customerBusinessContactRow
      }

      "silently succeed without changes when the customer target is missing" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

        customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          businessName = Some("Replacement Business"),
          customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
          customerBusinessContactsUpdate =
            List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
          customerBusinessContactsArchive =
            List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
        )
        val customerIDMissing = arbitrarySample[CustomerID]

        customerIDMissing shouldNot equal(customerBusinessDetailsRow.customerID)

        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerIDMissing,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "silently succeed without changes when the customer target is archived" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = true,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

        customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          businessName = Some("Replacement Business"),
          customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
          customerBusinessContactsUpdate =
            List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
          customerBusinessContactsArchive =
            List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
        )
        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "silently succeed without changes when the customer target is wrong type" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

        customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          businessName = Some("Replacement Business"),
          customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
          customerBusinessContactsUpdate =
            List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
          customerBusinessContactsArchive =
            List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
        )
        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerIndividualDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "silently succeed without changes when the customer target is foreign" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = arbitrarySample[OrganizationID],
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        customerBusinessDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBusinessContactIDUpdate  = arbitrarySample[CustomerBusinessContactID]
        val customerBusinessContactIDArchive = arbitrarySample[CustomerBusinessContactID]

        customerBusinessContactIDUpdate shouldNot equal(customerBusinessContactIDArchive)

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          businessName = Some("Replacement Business"),
          customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
          customerBusinessContactsUpdate =
            List(smithy.CustomerBusinessContactUpdate(customerBusinessContactIDUpdate.value)),
          customerBusinessContactsArchive =
            List(smithy.CustomerBusinessContactArchive(customerBusinessContactIDArchive.value)),
        )
        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "successfully ignore missing archived and foreign archive references" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        val customerBusinessDetailsRowOther = customerBusinessDetailsRow.copy(
          customerID = arbitrarySample[CustomerID],
          businessName = CustomerBusinessName.assume("Other Business"),
        )
        val customerBusinessContactRowArchived = customerBusinessContactRow.copy(isArchived = true)
        val customerBusinessContactRowOther    =
          customerBusinessContactRow.copy(customerID = customerBusinessDetailsRowOther.customerID)

        customerBusinessDetailsRowOther.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

        val customerBusinessContactIDMissing = arbitrarySample[CustomerBusinessContactID]

        customerBusinessContactIDMissing shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRows(
              List(customerBusinessDetailsRow, customerBusinessDetailsRowOther)
            )
          )
          .zioValue

        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(
              List(customerBusinessContactRowArchived, customerBusinessContactRowOther)
            )
          )
          .zioValue

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          customerBusinessContactsArchive = List(
            smithy.CustomerBusinessContactArchive(customerBusinessContactRowArchived.customerBusinessContactID.value),
            smithy.CustomerBusinessContactArchive(customerBusinessContactIDMissing.value),
          )
        )
        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.NoContent
        customerBookBusinessPutResponse.body.value shouldBe ()

        val customerBusinessDetailsRows =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue
        val customerBusinessDetailsRowUpdated =
          customerBusinessDetailsRows.find(_.customerID == customerBusinessDetailsRow.customerID).value

        customerBusinessDetailsRows should contain theSameElementsAs List(
          customerBusinessDetailsRow.copy(updatedAt = customerBusinessDetailsRowUpdated.updatedAt),
          customerBusinessDetailsRowOther,
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs List(
          customerBusinessContactRowArchived,
          customerBusinessContactRowOther,
        )
      }

      "fail with a ValidationError for invalid nested contact fields without any writes" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.assume("Original Business"),
          taxID = Some(CustomerTaxID.assume("OriginalTax")),
          emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
          phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
          addresses =
            List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          role = Some(arbitrarySample[CustomerBusinessContactRole]),
          email = Some(CustomerEmail.assume("contact@example.com")),
          phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
          isArchived = false,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
          businessName = Some("Replacement Business"),
          customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("")),
          customerBusinessContactsUpdate = List(
            smithy.CustomerBusinessContactUpdate(
              customerBusinessContactRow.customerBusinessContactID.value,
              role = Some(Nullable.Value("")),
            )
          ),
        )
        val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookBusinessPutResponse = gatewayClient
          .customerBookBusinessPut[smithy.ValidationError](
            customerBusinessDetailsRow.customerID,
            customerBookBusinessPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.BadRequest
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.ValidationError(fields =
          List("customerBusinessContactInsert", "customerBusinessContactUpdate")
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List(customerBusinessContactRow)
      }

      "fail with a ValidationError for duplicate and overlapping contact IDs without any writes" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
            .zioValue

          val customerBusinessContactUpdateSmithy =
            smithy.CustomerBusinessContactUpdate(customerBusinessContactRow.customerBusinessContactID.value)
          val customerBusinessContactArchiveSmithy =
            smithy.CustomerBusinessContactArchive(customerBusinessContactRow.customerBusinessContactID.value)
          val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Replacement Business"),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
            customerBusinessContactsUpdate =
              List(customerBusinessContactUpdateSmithy, customerBusinessContactUpdateSmithy),
            customerBusinessContactsArchive =
              List(customerBusinessContactArchiveSmithy, customerBusinessContactArchiveSmithy),
          )
          val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookBusinessPutResponse = gatewayClient
            .customerBookBusinessPut[smithy.ValidationError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessPutResponse.code shouldBe StatusCode.BadRequest
          customerBookBusinessPutResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List(
              "customerBusinessContactsUpdate",
              "customerBusinessContactsArchive",
              "customerBusinessContactsArchive",
              "customerBusinessContactsArchive",
            )
          )

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List(customerBusinessContactRow)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.BadRequest](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.BadRequest
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.Unauthorized
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.Unauthorized
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.Forbidden](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.Forbidden
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.Forbidden](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.Forbidden
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict on an active business name and roll back requested contact changes" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          val customerBusinessDetailsRowExisting = customerBusinessDetailsRow.copy(
            customerID = arbitrarySample[CustomerID],
            businessName = CustomerBusinessName.assume("Taken Business"),
          )

          customerBusinessDetailsRowExisting.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

          postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRows(
                List(customerBusinessDetailsRow, customerBusinessDetailsRowExisting)
              )
            )
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
            .zioValue

          val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
            businessName = Some("Taken Business"),
            customerBusinessContactsInsert = List(smithy.CustomerBusinessContactInsert("Inserted Contact")),
            customerBusinessContactsUpdate = List(
              smithy.CustomerBusinessContactUpdate(
                customerBusinessContactRow.customerBusinessContactID.value,
                fullName = Some("Updated Contact"),
              )
            ),
          )
          val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookBusinessPutResponse = gatewayClient
            .customerBookBusinessPut[smithy.Conflict](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessPutResponse.code shouldBe StatusCode.Conflict
          customerBookBusinessPutResponse.body.left.value shouldBe smithy.Conflict(
            message = "A customer with the given name already exists in this organization"
          )

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue should contain theSameElementsAs List(
            customerBusinessDetailsRow,
            customerBusinessDetailsRowExisting,
          )

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List(customerBusinessContactRow)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val customerBookBusinessPutRequestSmithy =
          smithy.CustomerBookBusinessPutRequest()

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessPutResponse =
          gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              customerBookBusinessPutRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError and roll back business and contacts when an update reference is missing" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )
          val customerBusinessContactIDInvalid = arbitrarySample[CustomerBusinessContactID]

          customerBusinessContactIDInvalid shouldNot equal(customerBusinessContactRow.customerBusinessContactID)

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
            .zioValue

          val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
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
          val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookBusinessPutResponse = gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
          customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List(customerBusinessContactRow)
      }

      "fail with an InternalServerError and roll back business and contacts when an update reference is archived" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )
          val customerBusinessContactRowInvalid = customerBusinessContactRow.copy(
            customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
            isArchived = true,
          )

          customerBusinessContactRowInvalid.customerBusinessContactID shouldNot equal(
            customerBusinessContactRow.customerBusinessContactID
          )

          val customerBusinessContactIDInvalid = customerBusinessContactRowInvalid.customerBusinessContactID

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(
                List(customerBusinessContactRow, customerBusinessContactRowInvalid)
              )
            )
            .zioValue

          val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
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
          val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookBusinessPutResponse = gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
          customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue should contain theSameElementsAs List(
            customerBusinessContactRow,
            customerBusinessContactRowInvalid,
          )
      }

      "fail with an InternalServerError and roll back business and contacts when an update reference is another business" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = CustomerBusinessName.assume("Original Business"),
            taxID = Some(CustomerTaxID.assume("OriginalTax")),
            emails = List(CustomerEmailEntryInput(CustomerEmail.assume("original@example.com"), isDefault = true)),
            phoneNumbers = List(CustomerPhoneNumberEntryInput(arbitrarySample[CustomerPhoneNumber], isDefault = true)),
            addresses =
              List(CustomerAddressEntry(AddressEntry(arbitrarySample[Address], AddressType.ShippingAndBilling))),
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(CustomerEmail.assume("contact@example.com")),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )
          val customerBusinessDetailsRowOther = customerBusinessDetailsRow.copy(
            customerID = arbitrarySample[CustomerID],
            businessName = CustomerBusinessName.assume("Other Business"),
          )
          val customerBusinessContactRowInvalid = customerBusinessContactRow.copy(
            customerID = customerBusinessDetailsRowOther.customerID,
            customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
          )

          customerBusinessDetailsRowOther.customerID shouldNot equal(customerBusinessDetailsRow.customerID)
          customerBusinessContactRowInvalid.customerBusinessContactID shouldNot equal(
            customerBusinessContactRow.customerBusinessContactID
          )

          val customerBusinessContactIDInvalid = customerBusinessContactRowInvalid.customerBusinessContactID

          postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRows(
                List(customerBusinessDetailsRow, customerBusinessDetailsRowOther)
              )
            )
            .zioValue

          postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(
                List(customerBusinessContactRow, customerBusinessContactRowInvalid)
              )
            )
            .zioValue

          val customerBookBusinessPutRequestSmithy = smithy.CustomerBookBusinessPutRequest(
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
          val accessJwt                       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookBusinessPutResponse = gatewayClient
            .customerBookBusinessPut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              customerBookBusinessPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessPutResponse.code shouldBe StatusCode.InternalServerError
          customerBookBusinessPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue should contain theSameElementsAs List(customerBusinessDetailsRow, customerBusinessDetailsRowOther)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue should contain theSameElementsAs List(
            customerBusinessContactRow,
            customerBusinessContactRowInvalid,
          )
      }

    }

    "PUT /customer-book/{customerID}/archive" should {
      "successfully archive a customer" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(organizationID = organizationUserRow.organizationID, isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
        customerBookArchivePutResponse.body.value shouldBe ()

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue

        customerIndividualDetailsRowsAll shouldBe List(
          customerIndividualDetailsRow.copy(
            isArchived = true,
            updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "silently succeed with no side effect when no customer matches the id" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
        customerBookArchivePutResponse.body.value shouldBe ()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe empty

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "successfully archive a business while retaining active and archived contacts in full" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val customerBusinessContactRowActive = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = false,
        )
        val customerBusinessContactRowArchived = customerBusinessContactRowActive.copy(
          customerBusinessContactID = arbitrarySample[CustomerBusinessContactID],
          isArchived = true,
        )

        customerBusinessContactRowArchived.customerBusinessContactID shouldNot equal(
          customerBusinessContactRowActive.customerBusinessContactID
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(
              List(customerBusinessContactRowActive, customerBusinessContactRowArchived)
            )
          )
          .zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookArchivePutResponse = gatewayClient
          .customerBookArchivePut[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
        customerBookArchivePutResponse.body.value shouldBe ()

        val customerBusinessDetailsRows =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue

        customerBusinessDetailsRows shouldBe List(
          customerBusinessDetailsRow.copy(
            isArchived = true,
            updatedAt = customerBusinessDetailsRows.head.updatedAt,
          )
        )
        customerBusinessDetailsRows.head should not equal customerBusinessDetailsRow

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs List(
          customerBusinessContactRowActive,
          customerBusinessContactRowArchived,
        )
      }

      "silently succeed without changing the complete customer or contacts when the customer is already archived" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = true,
          )
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

          val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookArchivePutResponse = gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
          customerBookArchivePutResponse.body.value shouldBe ()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List(customerBusinessContactRow)
      }

      "silently succeed without changing the complete customer or contacts when the customer is in another organization" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = arbitrarySample[OrganizationID],
            isArchived = false,
          )
          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
          )

          customerBusinessDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
            .zioValue

          val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookArchivePutResponse = gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookArchivePutResponse.code shouldBe StatusCode.NoContent
          customerBookArchivePutResponse.body.value shouldBe ()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List(customerBusinessContactRow)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(isArchived = false)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.BadRequest](
              customerIndividualDetailsRow.customerID,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.BadRequest
        customerBookArchivePutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue shouldBe
          List(customerIndividualDetailsRow)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              Some(organizationID),
              None,
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.Unauthorized
        customerBookArchivePutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.Unauthorized
        customerBookArchivePutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.Forbidden](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.Forbidden
        customerBookArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.Forbidden](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.Forbidden
        customerBookArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.InternalServerError
        customerBookArchivePutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookArchivePutResponse =
          gatewayClient
            .customerBookArchivePut[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookArchivePutResponse.code shouldBe StatusCode.InternalServerError
        customerBookArchivePutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }
    }

    "GET /customer-book/individual/{customerID}" should {
      "successfully return the customer individual's details" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val addressFull = Address(
          addressLine1 = Some(arbitrarySample[AddressLine1]),
          addressLine2 = Some(arbitrarySample[AddressLine2]),
          city = Some(arbitrarySample[City]),
          postalCode = Some(arbitrarySample[PostalCode]),
          country = Some(arbitrarySample[Country]),
        )
        val addressWithoutLine2          = addressFull.copy(addressLine2 = None)
        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
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

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.Ok
        customerBookIndividualGetResponse.body.value shouldBe smithy.CustomerBookIndividualGetResponse(
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

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.BadRequest](
              arbitrarySample[CustomerID],
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.BadRequest
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              None,
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.Forbidden](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.Forbidden
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError for an archived individual while retaining its complete state" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          val addressWithoutLine2          = addressFull.copy(addressLine2 = None)
          val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
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

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookIndividualGetResponse =
            gatewayClient
              .customerBookIndividualGet[smithy.InternalServerError](
                customerIndividualDetailsRow.customerID,
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )
              .zioValue

          customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
          customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List(customerIndividualDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the customer individual does not exist" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError for a business ID while retaining the business and its contacts" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
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

          val accessJwt                         = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookIndividualGetResponse = gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
          customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List(customerBusinessContactRow)
      }

      "fail with an InternalServerError for a foreign individual while retaining its complete row" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerIndividualDetailsRow =
            arbitrarySample[CustomerIndividualDetailsRow].copy(isArchived = false)

          customerIndividualDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            .zioValue

          val accessJwt                         = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookIndividualGetResponse = gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
          customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List(customerIndividualDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val accessJwt = jwtService.generateAccessToken(arbitrarySample[UserID]).zioValue

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookIndividualGetResponse =
          gatewayClient
            .customerBookIndividualGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookIndividualGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookIndividualGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }
    }

    "GET /customer-book/business/{customerID}" should {
      "successfully return complete business values and active contacts in creation-time and identifier order" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          val addressWithoutLine2        = addressFull.copy(addressLine2 = None)
          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
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

          val createdAtEarlierText                = "2026-01-01T10:00:00Z"
          val createdAtLaterText                  = "2026-01-02T10:00:00Z"
          val customerBusinessContactIDFirstText  = "00000000-0000-0000-0000-000000000001"
          val customerBusinessContactIDSecondText = "00000000-0000-0000-0000-000000000002"
          val createdAtEarlier                    = CreatedAt(java.time.Instant.parse(createdAtEarlierText))
          val createdAtLater                      = CreatedAt(java.time.Instant.parse(createdAtLaterText))
          val customerBusinessContactIDFirst      =
            CustomerBusinessContactID(java.util.UUID.fromString(customerBusinessContactIDFirstText))
          val customerBusinessContactIDSecond =
            CustomerBusinessContactID(java.util.UUID.fromString(customerBusinessContactIDSecondText))
          val customerBusinessContactRowEarlier = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            createdAt = createdAtEarlier,
            updatedAt = UpdatedAt(createdAtEarlier.value),
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(arbitrarySample[CustomerEmail]),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )
          val customerBusinessContactRowFirst = arbitrarySample[CustomerBusinessContactRow].copy(
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
          val customerBusinessContactRowSecond = arbitrarySample[CustomerBusinessContactRow].copy(
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
          val customerBusinessContactRowArchived = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            createdAt = createdAtEarlier,
            updatedAt = UpdatedAt(createdAtEarlier.value),
            isArchived = true,
          )

          val customerBusinessContactCount = 4
          Set(
            customerBusinessContactRowEarlier.customerBusinessContactID,
            customerBusinessContactRowFirst.customerBusinessContactID,
            customerBusinessContactRowSecond.customerBusinessContactID,
            customerBusinessContactRowArchived.customerBusinessContactID,
          ).size shouldBe customerBusinessContactCount

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowSecond)))
            .zioValue

          postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowArchived))
            )
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowFirst)))
            .zioValue

          postgresClient
            .executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowEarlier))
            )
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBusinessGetResponse = gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessGetResponse.code shouldBe StatusCode.Ok
          customerBookBusinessGetResponse.body.value shouldBe smithy.CustomerBookBusinessGetResponse(
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
            customerBusinessContacts =
              List(customerBusinessContactRowEarlier, customerBusinessContactRowFirst, customerBusinessContactRowSecond)
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

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue should contain theSameElementsAs List(
            customerBusinessContactRowSecond,
            customerBusinessContactRowArchived,
            customerBusinessContactRowFirst,
            customerBusinessContactRowEarlier,
          )
      }

      "successfully return a required empty contact list for a business with no contacts" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val addressFull = Address(
          addressLine1 = Some(arbitrarySample[AddressLine1]),
          addressLine2 = Some(arbitrarySample[AddressLine2]),
          city = Some(arbitrarySample[City]),
          postalCode = Some(arbitrarySample[PostalCode]),
          country = Some(arbitrarySample[Country]),
        )
        val addressWithoutLine2        = addressFull.copy(addressLine2 = None)
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
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

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessGetResponse = gatewayClient
          .customerBookBusinessGet[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.Ok
        customerBookBusinessGetResponse.body.value shouldBe smithy.CustomerBookBusinessGetResponse(
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

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs Nil
      }

      "successfully return a required empty contact list when all contacts are archived" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val addressFull = Address(
          addressLine1 = Some(arbitrarySample[AddressLine1]),
          addressLine2 = Some(arbitrarySample[AddressLine2]),
          city = Some(arbitrarySample[City]),
          postalCode = Some(arbitrarySample[PostalCode]),
          country = Some(arbitrarySample[Country]),
        )
        val addressWithoutLine2        = addressFull.copy(addressLine2 = None)
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
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

        val customerBusinessContactRowArchived = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
          isArchived = true,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRowArchived)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessGetResponse = gatewayClient
          .customerBookBusinessGet[smithy.InternalServerError](
            customerBusinessDetailsRow.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.Ok
        customerBookBusinessGetResponse.body.value shouldBe smithy.CustomerBookBusinessGetResponse(
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

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue should contain theSameElementsAs List(customerBusinessContactRowArchived)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.BadRequest](
              arbitrarySample[CustomerID],
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.BadRequest
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              None,
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.Unauthorized](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.Forbidden](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.Forbidden
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError for an archived business while retaining its complete state" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val addressFull = Address(
            addressLine1 = Some(arbitrarySample[AddressLine1]),
            addressLine2 = Some(arbitrarySample[AddressLine2]),
            city = Some(arbitrarySample[City]),
            postalCode = Some(arbitrarySample[PostalCode]),
            country = Some(arbitrarySample[Country]),
          )
          val addressWithoutLine2        = addressFull.copy(addressLine2 = None)
          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
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

          val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = customerBusinessDetailsRow.organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            role = Some(arbitrarySample[CustomerBusinessContactRole]),
            email = Some(arbitrarySample[CustomerEmail]),
            phoneNumber = Some(arbitrarySample[CustomerPhoneNumber]),
            isArchived = false,
          )

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBusinessGetResponse = gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
          customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue should contain theSameElementsAs List(customerBusinessContactRow)
      }

      "fail with an InternalServerError when the customer business does not exist" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError for an individual target while retaining its complete row" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerIndividualDetailsRow =
            arbitrarySample[CustomerIndividualDetailsRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
            )

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBusinessGetResponse = gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerIndividualDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
          customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List(customerIndividualDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil
      }

      "fail with an InternalServerError for a foreign business while retaining its complete row" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBusinessDetailsRow =
            arbitrarySample[CustomerBusinessDetailsRow].copy(isArchived = false)

          customerBusinessDetailsRow.organizationID shouldNot equal(organizationUserRow.organizationID)

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookBusinessGetResponse = gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              customerBusinessDetailsRow.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
          customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe Nil

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List(customerBusinessDetailsRow)

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val accessJwt = jwtService.generateAccessToken(arbitrarySample[UserID]).zioValue

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookBusinessGetResponse =
          gatewayClient
            .customerBookBusinessGet[smithy.InternalServerError](
              arbitrarySample[CustomerID],
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookBusinessGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookBusinessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }
    }

    "GET /customer-book/summary" should {
      "successfully return every active customer sorted case-insensitively by display name" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            fullName = CustomerFullName.assume("alpha individual"),
          )

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            businessName = CustomerBusinessName.assume("Zeta Business"),
          )

        val customerIndividualDetailsRowArchived = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(organizationID = organizationUserRow.organizationID, isArchived = true)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowArchived))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.InternalServerError](
              Nil,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.Ok

        customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(
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

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue should contain theSameElementsAs List(
          customerIndividualDetailsRow,
          customerIndividualDetailsRowArchived,
        )

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List(customerBusinessDetailsRow)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "successfully return an empty summary when the customer book is empty" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookSummaryGetResponse = gatewayClient
          .customerBookSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
        customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(customers = Nil)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "successfully filter requested active customers of both kinds once in display name order" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  =
            arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            fullName = CustomerFullName.assume("alpha individual"),
          )
          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
            businessName = CustomerBusinessName.assume("Beta Business"),
          )
          val customerIndividualDetailsRowArchived = customerIndividualDetailsRow.copy(
            customerID = arbitrarySample[CustomerID],
            isArchived = true,
          )
          val customerBusinessDetailsRowForeign = customerBusinessDetailsRow.copy(
            organizationID = arbitrarySample[OrganizationID],
            customerID = arbitrarySample[CustomerID],
          )
          val customerBusinessDetailsRowUnrequested = customerBusinessDetailsRow.copy(
            customerID = arbitrarySample[CustomerID],
            businessName = CustomerBusinessName.assume("Aardvark Unrequested"),
          )
          val customerIDUnknown             = arbitrarySample[CustomerID]
          val customerIndividualDetailsRows = List(customerIndividualDetailsRowArchived, customerIndividualDetailsRow)
          val customerBusinessDetailsRows   =
            List(customerBusinessDetailsRowUnrequested, customerBusinessDetailsRowForeign, customerBusinessDetailsRow)
          val uniqueCustomerIDsCountExpected = 6
          val customerIDs                    = List(
            customerBusinessDetailsRow.customerID,
            customerIndividualDetailsRow.customerID,
            customerIndividualDetailsRow.customerID,
            customerIndividualDetailsRowArchived.customerID,
            customerBusinessDetailsRowForeign.customerID,
            customerIDUnknown,
          )

          customerBusinessDetailsRowForeign.organizationID shouldNot equal(customerBusinessDetailsRow.organizationID)
          (customerIndividualDetailsRows.map(_.customerID) ++ customerBusinessDetailsRows.map(
            _.customerID
          ) :+ customerIDUnknown).distinct.size shouldBe uniqueCustomerIDsCountExpected

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRows(customerBusinessDetailsRows))
            .zioValue

          val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val customerBookSummaryGetResponse = gatewayClient
            .customerBookSummaryGet[smithy.InternalServerError](
              customerIDs,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
          customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(
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

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue should contain theSameElementsAs customerIndividualDetailsRows

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue should contain theSameElementsAs customerBusinessDetailsRows

          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe Nil
      }

      "successfully return no matches instead of all customers for an unknown requested ID" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  =
          arbitrarySample[OrganizationUserRow].copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val customerIDUnknown = arbitrarySample[CustomerID]

        customerIDUnknown shouldNot equal(customerIndividualDetailsRow.customerID)

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val customerBookSummaryGetResponse = gatewayClient
          .customerBookSummaryGet[smithy.InternalServerError](
            List(customerIDUnknown),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.Ok
        customerBookSummaryGetResponse.body.value shouldBe smithy.CustomerBookSummaryGetResponse(customers = Nil)

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List(customerIndividualDetailsRow)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.BadRequest](Nil, None, Some(accessJwt.accessToken))
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.BadRequest
        customerBookSummaryGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.Unauthorized](Nil, Some(arbitrarySample[OrganizationID]), None)
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookSummaryGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.Unauthorized](
              Nil,
              Some(arbitrarySample[OrganizationID]),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookSummaryGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.Forbidden](
              Nil,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.Forbidden
        customerBookSummaryGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val accessJwt = jwtService.generateAccessToken(arbitrarySample[UserID]).zioValue

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.InternalServerError](
              Nil,
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookSummaryGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookSummaryGetResponse =
          gatewayClient
            .customerBookSummaryGet[smithy.InternalServerError](
              Nil,
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookSummaryGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookSummaryGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }
    }
    "GET /customer-book/uniqueness" should {
      "successfully report an available name in an empty customer book without reserving it" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(customerID = None)

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.InternalServerError](
            Some(customerBookUniquenessGetRequest.name.value),
            Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
            customerBookUniquenessGetRequest.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
        customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
          true
        )

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

      "successfully report an active individual name as taken" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
          name = customerIndividualDetailsRow.fullName.to[CustomerName],
          customerType = CustomerType.Individual,
          customerID = None,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.InternalServerError](
            Some(customerBookUniquenessGetRequest.name.value),
            Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
            customerBookUniquenessGetRequest.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
        customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
          false
        )

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

      "successfully report an active business name as taken and retain its contacts" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
        )
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
          name = customerBusinessDetailsRow.businessName.to[CustomerName],
          customerType = CustomerType.Business,
          customerID = None,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.InternalServerError](
            Some(customerBookUniquenessGetRequest.name.value),
            Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
            customerBookUniquenessGetRequest.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
        customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
          false
        )

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

      "successfully ignore matching names belonging only to another kind archived customers or another organization" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
            customerType = CustomerType.Individual,
            customerID = None,
          )
          val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            businessName = customerBookUniquenessGetRequest.name.to[CustomerBusinessName],
            isArchived = false,
          )
          val customerIndividualDetailsRowArchived = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            fullName = customerBookUniquenessGetRequest.name.to[CustomerFullName],
            isArchived = true,
          )
          val customerIndividualDetailsRowForeign = arbitrarySample[CustomerIndividualDetailsRow].copy(
            fullName = customerBookUniquenessGetRequest.name.to[CustomerFullName],
            isArchived = false,
          )
          val customerIndividualDetailsRows =
            List(customerIndividualDetailsRowArchived, customerIndividualDetailsRowForeign)

          customerIndividualDetailsRowForeign.organizationID shouldNot equal(organizationUserRow.organizationID)
          customerIndividualDetailsRowArchived.customerID shouldNot equal(customerBusinessDetailsRow.customerID)

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
            .zioValue

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRows(customerIndividualDetailsRows))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookUniquenessGetResponse = gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
            true
          )

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

      "successfully exclude the current active customer when checking its own name" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
          organizationID = customerBusinessDetailsRow.organizationID,
          customerID = customerBusinessDetailsRow.customerID,
        )
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
          name = customerBusinessDetailsRow.businessName.to[CustomerName],
          customerType = CustomerType.Business,
          customerID = Some(customerBusinessDetailsRow.customerID),
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.InternalServerError](
            Some(customerBookUniquenessGetRequest.name.value),
            Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
            customerBookUniquenessGetRequest.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
        customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
          true
        )

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

      "successfully report a matching active name as taken when the exclusion ID is unrelated" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          val customerIDUnrelated              = arbitrarySample[CustomerID]
          val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
            name = customerIndividualDetailsRow.fullName.to[CustomerName],
            customerType = CustomerType.Individual,
            customerID = Some(customerIDUnrelated),
          )

          customerIDUnrelated shouldNot equal(customerIndividualDetailsRow.customerID)

          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookUniquenessGetResponse = gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
          customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
            false
          )

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

      "successfully treat differently cased names as distinct" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBusinessNameRaw    = "Case Sensitive Name"
        val customerNameRaw            = "case sensitive name"
        val customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow].copy(
          organizationID = organizationUserRow.organizationID,
          businessName = CustomerBusinessName.either(customerBusinessNameRaw).toOption.value,
          isArchived = false,
        )
        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest].copy(
          name = CustomerName.either(customerNameRaw).toOption.value,
          customerType = CustomerType.Business,
          customerID = None,
        )

        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.InternalServerError](
            Some(customerBookUniquenessGetRequest.name.value),
            Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
            customerBookUniquenessGetRequest.customerID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Ok
        customerBookUniquenessGetResponse.body.value shouldBe smithy.CustomerBookUniquenessGetResponse(isNameUnique =
          true
        )

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

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse =
          gatewayClient
            .customerBookUniquenessGet[smithy.BadRequest](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.BadRequest
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError naming both missing required query fields" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.ValidationError](
            None,
            None,
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.BadRequest
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(fields =
          List("name", "customerType")
        )

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

      "fail with a ValidationError for an invalid supplied name" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]
        val nameInvalid                      = ""

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse = gatewayClient
          .customerBookUniquenessGet[smithy.ValidationError](
            Some(nameInvalid),
            Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.BadRequest
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name"))

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

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

        val customerBookUniquenessGetResponse =
          gatewayClient
            .customerBookUniquenessGet[smithy.Unauthorized](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(arbitrarySample[OrganizationID]),
              None,
            )
            .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

        val customerBookUniquenessGetResponse =
          gatewayClient
            .customerBookUniquenessGet[smithy.Unauthorized](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(arbitrarySample[OrganizationID]),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Unauthorized
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse =
          gatewayClient
            .customerBookUniquenessGet[smithy.Forbidden](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.Forbidden
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

        val accessJwt = jwtService.generateAccessToken(arbitrarySample[UserID]).zioValue

        val customerBookUniquenessGetResponse =
          gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val customerBookUniquenessGetRequest = arbitrarySample[CustomerBookUniquenessGetRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookUniquenessGetResponse =
          gatewayClient
            .customerBookUniquenessGet[smithy.InternalServerError](
              Some(customerBookUniquenessGetRequest.name.value),
              Some(customerBookUniquenessGetRequest.customerType.transformInto[smithy.CustomerType]),
              customerBookUniquenessGetRequest.customerID,
              Some(arbitrarySample[OrganizationID]),
              Some(accessJwt.accessToken),
            )
            .zioValue

        customerBookUniquenessGetResponse.code shouldBe StatusCode.InternalServerError
        customerBookUniquenessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue shouldBe Nil

        postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue shouldBe Nil
      }
    }

  }
}
