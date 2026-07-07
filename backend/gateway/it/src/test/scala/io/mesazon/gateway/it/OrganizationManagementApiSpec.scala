package io.mesazon.gateway.it

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.{GatewayClientConfig, given}
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.utils.MailHogClient.MailHogClientConfig
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.*
import zio.*

class OrganizationManagementApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices =
    GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices ++ MailHogClient.ExposedServices

  case class Context(
      gatewayClient: GatewayClient,
      mailHogClient: MailHogClient,
      postgresClient: PostgreSQLTestClient,
      repositoryConfig: RepositoryConfig,
      userDetailsQueries: UserDetailsQueries,
      organizationDetailsQueries: OrganizationDetailsQueries,
      organizationUserQueries: OrganizationUserQueries,
      jwtService: JwtService,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      mailHogClientConfig    = MailHogClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      mailHogClient <- ZIO
        .service[MailHogClient]
        .provide(MailHogClient.live, HttpClientZioBackend.layer(), ZLayer.succeed(mailHogClientConfig))
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
      organizationDetailsQueries <- ZIO
        .service[OrganizationDetailsQueries]
        .provide(OrganizationDetailsQueries.live, RepositoryConfig.live, appNameLive)
      organizationUserQueries <- ZIO
        .service[OrganizationUserQueries]
        .provide(OrganizationUserQueries.live, RepositoryConfig.live, appNameLive)
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
      mailHogClient,
      postgreSQLClient,
      repositoryConfig,
      userDetailsQueries,
      organizationDetailsQueries,
      organizationUserQueries,
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
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    mailHogClient.clearInbox().zioValue

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue
  }

  "Organization Management Service API" when {
    "POST /create/organization" should {
      "successfully create organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganization = arbitrarySample[CreateOrganization]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.InternalServerError](
              createOrganization.name,
              createOrganization.slug,
              createOrganization.email,
              createOrganization.phoneNumber,
              createOrganization.addressLine1,
              createOrganization.addressLine2,
              createOrganization.city,
              createOrganization.postalCode,
              createOrganization.country,
              Some(accessJwt.accessToken),
            )
            .zioValue

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 1
        organizationDetailsRowsAll.head shouldBe OrganizationDetailsRow(
          organizationID = organizationDetailsRowsAll.head.organizationID,
          name = createOrganization.name,
          slug = createOrganization.slug,
          email = createOrganization.email,
          phoneNumber = createOrganization.phoneNumber,
          organizationStage = OrganizationStage.DetailsProvided,
          addressLine1 = createOrganization.addressLine1,
          addressLine2 = createOrganization.addressLine2,
          city = createOrganization.city,
          postalCode = createOrganization.postalCode,
          country = createOrganization.country,
          logoOriginalBucketKey = None,
          logoNormalizedBucketKey = None,
          logoOriginalFileName = None,
          createdAt = organizationDetailsRowsAll.head.createdAt,
          updatedAt = organizationDetailsRowsAll.head.updatedAt,
        )

        createOrganizationPostResponse.code shouldBe StatusCode.Ok
        createOrganizationPostResponse.body.value shouldBe smithy.CreateOrganizationPostResponse(organizationID =
          organizationDetailsRowsAll.head.organizationID.value
        )

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting).zioValue

        organizationUserRowsAll should have size 1
        organizationUserRowsAll.head shouldBe OrganizationUserRow(
          organizationDetailsRowsAll.head.organizationID,
          userDetailsRow.userID,
          UserRole.Owner,
          organizationUserRowsAll.head.createdAt,
          organizationUserRowsAll.head.updatedAt,
        )
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganization = arbitrarySample[CreateOrganization]
          .copy(name = OrganizationName.assume(""))

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.ValidationError](
              createOrganization.name,
              createOrganization.slug,
              createOrganization.email,
              createOrganization.phoneNumber,
              createOrganization.addressLine1,
              createOrganization.addressLine2,
              createOrganization.city,
              createOrganization.postalCode,
              createOrganization.country,
              Some(accessJwt.accessToken),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.BadRequest
        createOrganizationPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name"))

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with BadRequest when access token is missing" in withContext { context =>
        import context.*

        val createOrganization = arbitrarySample[CreateOrganization]

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.BadRequest](
              createOrganization.name,
              createOrganization.slug,
              createOrganization.email,
              createOrganization.phoneNumber,
              createOrganization.addressLine1,
              createOrganization.addressLine2,
              createOrganization.city,
              createOrganization.postalCode,
              createOrganization.country,
              None,
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.BadRequest
        createOrganizationPostResponse.body.left.value shouldBe smithy.BadRequest()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val createOrganization = arbitrarySample[CreateOrganization]

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.Unauthorized](
              createOrganization.name,
              createOrganization.slug,
              createOrganization.email,
              createOrganization.phoneNumber,
              createOrganization.addressLine1,
              createOrganization.addressLine2,
              createOrganization.city,
              createOrganization.postalCode,
              createOrganization.country,
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.Unauthorized
        createOrganizationPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganization = arbitrarySample[CreateOrganization]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.Forbidden](
              createOrganization.name,
              createOrganization.slug,
              createOrganization.email,
              createOrganization.phoneNumber,
              createOrganization.addressLine1,
              createOrganization.addressLine2,
              createOrganization.city,
              createOrganization.postalCode,
              createOrganization.country,
              Some(accessJwt.accessToken),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.Forbidden
        createOrganizationPostResponse.body.left.value shouldBe smithy.Forbidden()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0
      }

      "fail with InternalServerError when organization slug already exists" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganization = arbitrarySample[CreateOrganization]

        val existingOrganizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(slug = createOrganization.slug)

        postgresClient.executeQuery(organizationDetailsQueries.insert(existingOrganizationDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.InternalServerError](
              createOrganization.name,
              createOrganization.slug,
              createOrganization.email,
              createOrganization.phoneNumber,
              createOrganization.addressLine1,
              createOrganization.addressLine2,
              createOrganization.city,
              createOrganization.postalCode,
              createOrganization.country,
              Some(accessJwt.accessToken),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.InternalServerError
        createOrganizationPostResponse.body.left.value shouldBe smithy.InternalServerError()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 1

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }
  }
}
