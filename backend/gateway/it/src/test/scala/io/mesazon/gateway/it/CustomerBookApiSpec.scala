package io.mesazon.gateway.it

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.{GatewayClientConfig, given}
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import sttp.model.*
import zio.*

class CustomerBookApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      CustomerBookSmithyArbitraries,
      RepositoryArbitraries {

  override def exposedServices =
    GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices

  case class Context(
      gatewayClient: GatewayClient,
      postgresClient: PostgreSQLTestClient,
      repositoryConfig: RepositoryConfig,
      userDetailsQueries: UserDetailsQueries,
      organizationUserQueries: OrganizationUserQueries,
      customerBookQueries: CustomerBookQueries,
      jwtService: JwtService,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
      organizationUserQueries <- ZIO
        .service[OrganizationUserQueries]
        .provide(OrganizationUserQueries.live, RepositoryConfig.live, appNameLive)
      customerBookQueries <- ZIO
        .service[CustomerBookQueries]
        .provide(CustomerBookQueries.live, RepositoryConfig.live, appNameLive)
      jwtService <- ZIO
        .service[JwtService]
        .provide(
          JwtService.live,
          JwtConfig.live,
          IDGenerator.liveUUIDv7,
          TimeProvider.liveSystemUTC,
          appNameLive,
        )
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      repositoryConfig,
      userDetailsQueries,
      organizationUserQueries,
      customerBookQueries,
      jwtService,
    )

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually(
      gatewayClient.readiness.zioValue shouldBe StatusCode.NoContent
    )

    eventually(
      ZIO
        .foreach(repositoryConfig.allTableNames)(tableName =>
          postgresClient.checkIfTableExists(repositoryConfig.schema, tableName)
        )
        .zioValue should contain only true
    )
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue
  }

  "Customer Book Service API" when {
    "POST /insert/customer-individual" should {
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

        val insertCustomerIndividualPostRequest = arbitrarySample[InsertCustomerIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.InternalServerError](
              insertCustomerIndividualPostRequest.transformInto[smithy.InsertCustomerIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.NoContent

        val customerRowsAll = postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue
        customerRowsAll should have size 1
        customerRowsAll.head shouldBe CustomerRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerRowsAll.head.customerID,
          customerType = CustomerType.Individual,
          status = CustomerStatus.Active,
          createdAt = customerRowsAll.head.createdAt,
          updatedAt = customerRowsAll.head.updatedAt,
        )

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue
        customerIndividualDetailsRowsAll should have size 1
        customerIndividualDetailsRowsAll.head shouldBe CustomerIndividualDetailsRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerRowsAll.head.customerID,
          fullName = insertCustomerIndividualPostRequest.fullName,
          emails = insertCustomerIndividualPostRequest.emails.map(entry =>
            CustomerEmailEntryInput(entry.email, entry.isDefault)
          ),
          phoneNumbers = insertCustomerIndividualPostRequest.phoneNumbers.map(entry =>
            CustomerPhoneNumberEntryInput(entry.phoneNumber, entry.isDefault)
          ),
          addressLine1 = insertCustomerIndividualPostRequest.addressLine1,
          addressLine2 = insertCustomerIndividualPostRequest.addressLine2,
          city = insertCustomerIndividualPostRequest.city,
          postalCode = insertCustomerIndividualPostRequest.postalCode,
          country = insertCustomerIndividualPostRequest.country,
          createdAt = customerIndividualDetailsRowsAll.head.createdAt,
          updatedAt = customerIndividualDetailsRowsAll.head.updatedAt,
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

        val insertCustomerIndividualPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = "")

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.ValidationError](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.BadRequest
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("fullName"))

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.BadRequest](
              insertCustomerIndividualPostRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.BadRequest
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]
        val organizationID                            = arbitrarySample[OrganizationID]

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.Unauthorized](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.Unauthorized
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]
        val organizationID                            = arbitrarySample[OrganizationID]

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.Unauthorized](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.Unauthorized
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.Forbidden](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.Forbidden
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.Forbidden](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.Forbidden
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerIndividualPostRequest = arbitrarySample[InsertCustomerIndividualPostRequest]

        val customerRowExisting = arbitrarySample[CustomerRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            customerType = CustomerType.Individual,
            status = CustomerStatus.Active,
          )
        val customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            customerID = customerRowExisting.customerID,
            fullName = insertCustomerIndividualPostRequest.fullName,
          )

        postgresClient.executeQuery(customerBookQueries.insertCustomerRow(customerRowExisting)).zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.Conflict](
              insertCustomerIndividualPostRequest.transformInto[smithy.InsertCustomerIndividualPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.Conflict
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.Conflict(message =
          "A customer with the given full name already exists in this organization"
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue.map(_.customerID) shouldBe
          List(customerRowExisting.customerID)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.InternalServerError](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.InternalServerError
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val insertCustomerIndividualPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualPostResponse =
          gatewayClient
            .insertCustomerIndividualPost[smithy.InternalServerError](
              insertCustomerIndividualPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualPostResponse.code shouldBe StatusCode.InternalServerError
        insertCustomerIndividualPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }
    }

    "POST /insert/customer-individuals" should {
      "successfully insert a batch of customer individuals" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerFullName1 = arbitrarySample[CustomerFullName]
        val customerFullName2 = CustomerFullName.assume(s"${customerFullName1.value.take(253)}-2")
        customerFullName1 shouldNot equal(customerFullName2)

        val insertCustomerIndividualPostRequest1 = arbitrarySample[InsertCustomerIndividualPostRequest]
          .copy(fullName = customerFullName1)
        val insertCustomerIndividualPostRequest2 = arbitrarySample[InsertCustomerIndividualPostRequest]
          .copy(fullName = customerFullName2)
        val insertCustomerIndividualsPostRequest = InsertCustomerIndividualsPostRequest(
          customerIndividuals = List(insertCustomerIndividualPostRequest1, insertCustomerIndividualPostRequest2)
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.InternalServerError](
              insertCustomerIndividualsPostRequest.transformInto[smithy.InsertCustomerIndividualsPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.NoContent

        val customerRowsAll = postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue
        customerRowsAll should have size 2

        val customerIndividualDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting).zioValue
        customerIndividualDetailsRowsAll should have size 2

        List(insertCustomerIndividualPostRequest1, insertCustomerIndividualPostRequest2).foreach {
          insertCustomerIndividualPostRequest =>
            val customerIndividualDetailsRow = customerIndividualDetailsRowsAll
              .find(_.fullName == insertCustomerIndividualPostRequest.fullName)
              .value
            customerIndividualDetailsRow shouldBe CustomerIndividualDetailsRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerIndividualDetailsRow.customerID,
              fullName = insertCustomerIndividualPostRequest.fullName,
              emails = insertCustomerIndividualPostRequest.emails.map(entry =>
                CustomerEmailEntryInput(entry.email, entry.isDefault)
              ),
              phoneNumbers = insertCustomerIndividualPostRequest.phoneNumbers.map(entry =>
                CustomerPhoneNumberEntryInput(entry.phoneNumber, entry.isDefault)
              ),
              addressLine1 = insertCustomerIndividualPostRequest.addressLine1,
              addressLine2 = insertCustomerIndividualPostRequest.addressLine2,
              city = insertCustomerIndividualPostRequest.city,
              postalCode = insertCustomerIndividualPostRequest.postalCode,
              country = insertCustomerIndividualPostRequest.country,
              createdAt = customerIndividualDetailsRow.createdAt,
              updatedAt = customerIndividualDetailsRow.updatedAt,
            )

            val customerRow = customerRowsAll.find(_.customerID == customerIndividualDetailsRow.customerID).value
            customerRow shouldBe CustomerRow(
              organizationID = organizationUserRow.organizationID,
              customerID = customerIndividualDetailsRow.customerID,
              customerType = CustomerType.Individual,
              status = CustomerStatus.Active,
              createdAt = customerRow.createdAt,
              updatedAt = customerRow.updatedAt,
            )
        }
      }

      "fail with a ValidationError when a customer individual in the batch is invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCustomerIndividualsPostRequestSmithy = smithy.InsertCustomerIndividualsPostRequest(
          customerIndividuals = List(arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = ""))
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.ValidationError](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.BadRequest
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
          List("customerIndividuals")
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.BadRequest](
              insertCustomerIndividualsPostRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.BadRequest
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]
        val organizationID                             = arbitrarySample[OrganizationID]

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.Unauthorized](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.Unauthorized
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]
        val organizationID                             = arbitrarySample[OrganizationID]

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.Unauthorized](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.Unauthorized
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.Forbidden](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.Forbidden
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.Forbidden](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.Forbidden
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with a Conflict when a customer individual in the batch has a full name that already exists, rolling back the whole batch" in withContext {
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

          val customerFullNameConflicting = arbitrarySample[CustomerFullName]
          val customerFullNameNew         = CustomerFullName.assume(s"${customerFullNameConflicting.value.take(253)}-N")
          customerFullNameNew shouldNot equal(customerFullNameConflicting)

          val customerRowExisting = arbitrarySample[CustomerRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              customerType = CustomerType.Individual,
              status = CustomerStatus.Active,
            )
          val customerIndividualDetailsRowExisting = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(
              organizationID = organizationUserRow.organizationID,
              customerID = customerRowExisting.customerID,
              fullName = customerFullNameConflicting,
            )

          postgresClient.executeQuery(customerBookQueries.insertCustomerRow(customerRowExisting)).zioValue
          postgresClient
            .executeQuery(customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRowExisting))
            .zioValue

          val insertCustomerIndividualsPostRequest = InsertCustomerIndividualsPostRequest(
            customerIndividuals = List(
              arbitrarySample[InsertCustomerIndividualPostRequest].copy(fullName = customerFullNameNew),
              arbitrarySample[InsertCustomerIndividualPostRequest].copy(fullName = customerFullNameConflicting),
            )
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val insertCustomerIndividualsPostResponse =
            gatewayClient
              .insertCustomerIndividualsPost[smithy.Conflict](
                insertCustomerIndividualsPostRequest.transformInto[smithy.InsertCustomerIndividualsPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )
              .zioValue

          insertCustomerIndividualsPostResponse.code shouldBe StatusCode.Conflict
          insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A customer with the given full name already exists in this organization"
          )

          postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue.map(_.customerID) shouldBe
            List(customerRowExisting.customerID)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.InternalServerError](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.InternalServerError
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val insertCustomerIndividualsPostRequestSmithy = arbitrarySample[smithy.InsertCustomerIndividualsPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerIndividualsPostResponse =
          gatewayClient
            .insertCustomerIndividualsPost[smithy.InternalServerError](
              insertCustomerIndividualsPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerIndividualsPostResponse.code shouldBe StatusCode.InternalServerError
        insertCustomerIndividualsPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }
    }

    "POST /insert/customer-business" should {
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

        val insertCustomerBusinessContact = arbitrarySample[InsertCustomerBusinessContact]
        val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]
          .copy(customerBusinessContacts = List(insertCustomerBusinessContact))

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.InternalServerError](
              insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.NoContent

        val customerRowsAll = postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue
        customerRowsAll should have size 1
        customerRowsAll.head shouldBe CustomerRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerRowsAll.head.customerID,
          customerType = CustomerType.Business,
          status = CustomerStatus.Active,
          createdAt = customerRowsAll.head.createdAt,
          updatedAt = customerRowsAll.head.updatedAt,
        )

        val customerBusinessDetailsRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting).zioValue
        customerBusinessDetailsRowsAll should have size 1
        customerBusinessDetailsRowsAll.head shouldBe CustomerBusinessDetailsRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerRowsAll.head.customerID,
          businessName = insertCustomerBusinessPostRequest.businessName,
          emails = insertCustomerBusinessPostRequest.emails.map(entry =>
            CustomerEmailEntryInput(entry.email, entry.isDefault)
          ),
          phoneNumbers = insertCustomerBusinessPostRequest.phoneNumbers.map(entry =>
            CustomerPhoneNumberEntryInput(entry.phoneNumber, entry.isDefault)
          ),
          taxID = insertCustomerBusinessPostRequest.taxID,
          addressLine1 = insertCustomerBusinessPostRequest.addressLine1,
          addressLine2 = insertCustomerBusinessPostRequest.addressLine2,
          city = insertCustomerBusinessPostRequest.city,
          postalCode = insertCustomerBusinessPostRequest.postalCode,
          country = insertCustomerBusinessPostRequest.country,
          createdAt = customerBusinessDetailsRowsAll.head.createdAt,
          updatedAt = customerBusinessDetailsRowsAll.head.updatedAt,
        )

        val customerBusinessContactRowsAll =
          postgresClient.executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting).zioValue
        customerBusinessContactRowsAll should have size 1
        customerBusinessContactRowsAll.head shouldBe CustomerBusinessContactRow(
          organizationID = organizationUserRow.organizationID,
          customerID = customerRowsAll.head.customerID,
          customerBusinessContactID = customerBusinessContactRowsAll.head.customerBusinessContactID,
          fullName = insertCustomerBusinessContact.fullName,
          role = insertCustomerBusinessContact.role,
          email = insertCustomerBusinessContact.email,
          phoneNumber = insertCustomerBusinessContact.phoneNumber,
          createdAt = customerBusinessContactRowsAll.head.createdAt,
          updatedAt = customerBusinessContactRowsAll.head.updatedAt,
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

        val insertCustomerBusinessPostRequestSmithy =
          arbitrarySample[smithy.InsertCustomerBusinessPostRequest].copy(businessName = "")

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.ValidationError](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.BadRequest
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("businessName"))

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.BadRequest](
              insertCustomerBusinessPostRequestSmithy,
              None,
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.BadRequest
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]
        val organizationID                          = arbitrarySample[OrganizationID]

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.Unauthorized](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationID),
              None,
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.Unauthorized
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]
        val organizationID                          = arbitrarySample[OrganizationID]

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.Unauthorized](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationID),
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.Unauthorized
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.Forbidden](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.Forbidden
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.Forbidden](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.Forbidden
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
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

        val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]
          .copy(customerBusinessContacts = List.empty)

        val customerRowExisting = arbitrarySample[CustomerRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            customerType = CustomerType.Business,
            status = CustomerStatus.Active,
          )
        val customerBusinessDetailsRowExisting = arbitrarySample[CustomerBusinessDetailsRow]
          .copy(
            organizationID = organizationUserRow.organizationID,
            customerID = customerRowExisting.customerID,
            businessName = insertCustomerBusinessPostRequest.businessName,
          )

        postgresClient.executeQuery(customerBookQueries.insertCustomerRow(customerRowExisting)).zioValue
        postgresClient
          .executeQuery(customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRowExisting))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.Conflict](
              insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.Conflict
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Conflict(message =
          "A customer with the given business name already exists in this organization"
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue.map(_.customerID) shouldBe
          List(customerRowExisting.customerID)
      }

      "fail with a Conflict when two inline contacts share the same email, rolling back the whole insert" in withContext {
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

          val customerEmail = arbitrarySample[CustomerEmail]
          val insertCustomerBusinessContact1 = arbitrarySample[InsertCustomerBusinessContact]
            .copy(email = Some(customerEmail), phoneNumber = None)
          val insertCustomerBusinessContact2 = arbitrarySample[InsertCustomerBusinessContact]
            .copy(email = Some(customerEmail), phoneNumber = None)
          val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]
            .copy(customerBusinessContacts = List(insertCustomerBusinessContact1, insertCustomerBusinessContact2))

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val insertCustomerBusinessPostResponse =
            gatewayClient
              .insertCustomerBusinessPost[smithy.Conflict](
                insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )
              .zioValue

          insertCustomerBusinessPostResponse.code shouldBe StatusCode.Conflict
          insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A business contact with the given email already exists for this customer"
          )

          postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with a Conflict when two inline contacts share the same phone number, rolling back the whole insert" in withContext {
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

          val customerPhoneNumber = arbitrarySample[CustomerPhoneNumber]
          val insertCustomerBusinessContact1 = arbitrarySample[InsertCustomerBusinessContact]
            .copy(email = None, phoneNumber = Some(customerPhoneNumber))
          val insertCustomerBusinessContact2 = arbitrarySample[InsertCustomerBusinessContact]
            .copy(email = None, phoneNumber = Some(customerPhoneNumber))
          val insertCustomerBusinessPostRequest = arbitrarySample[InsertCustomerBusinessPostRequest]
            .copy(customerBusinessContacts = List(insertCustomerBusinessContact1, insertCustomerBusinessContact2))

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val insertCustomerBusinessPostResponse =
            gatewayClient
              .insertCustomerBusinessPost[smithy.Conflict](
                insertCustomerBusinessPostRequest.transformInto[smithy.InsertCustomerBusinessPostRequest],
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )
              .zioValue

          insertCustomerBusinessPostResponse.code shouldBe StatusCode.Conflict
          insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A business contact with the given phone number already exists for this customer"
          )

          postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.InternalServerError](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.InternalServerError
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]

        val insertCustomerBusinessPostRequestSmithy = arbitrarySample[smithy.InsertCustomerBusinessPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCustomerBusinessPostResponse =
          gatewayClient
            .insertCustomerBusinessPost[smithy.InternalServerError](
              insertCustomerBusinessPostRequestSmithy,
              Some(organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

        insertCustomerBusinessPostResponse.code shouldBe StatusCode.InternalServerError
        insertCustomerBusinessPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerRowsTesting).zioValue should have size 0
      }
    }
  }
}
