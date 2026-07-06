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
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.*
import sttp.model.*
import zio.*
import zio.stream.ZStream

class FileApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices =
    GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices ++ S3TestClient.ExposedServices

  // Mirrors the gateway container's organization-s3-client config (application.conf)
  private val organizationLogoBucket           = "organization-logo-bucket"
  private val organizationLogoBucketPathPrefix = "organization/logos"

  case class Context(
      gatewayClient: GatewayClient,
      postgresClient: PostgreSQLTestClient,
      s3TestClient: S3TestClient,
      repositoryConfig: RepositoryConfig,
      organizationDetailsQueries: OrganizationDetailsQueries,
      userDetailsQueries: UserDetailsQueries,
      jwtService: JwtService,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      s3TestClientConfig     = S3TestClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      s3TestClient <- ZIO
        .service[S3TestClient]
        .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      organizationDetailsQueries <- ZIO
        .service[OrganizationDetailsQueries]
        .provide(OrganizationDetailsQueries.live, RepositoryConfig.live, appNameLive)
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
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
      s3TestClient,
      repositoryConfig,
      organizationDetailsQueries,
      userDetailsQueries,
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

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue

    s3TestClient.emptyAllBuckets().zioValue
  }

  "File Service API" when {
    "/upload/organization/logo/{organizationID}" should {
      "upload the logo and store the original and normalized objects in S3" in withContext { context =>
        import context.*

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoOriginalBucketKey = None,
            logoNormalizedBucketKey = None,
            logoOriginalFileName = None,
          )

        postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = organizationDetailsRow.organizationID
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            organizationID,
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Ok

        assert(uploadOrganizationLogoResponse.body.isRight)

        val expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

        val logoOriginalObjectBytes = s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/original")
          .zioValue

        val logoNormalizedObjectBytes = s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioValue

        logoOriginalObjectBytes shouldBe logoBytes
        logoNormalizedObjectBytes.size should be > 0

        val organizationDetailsRowUpdated = postgresClient
          .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
          .zioValue
          .head

        organizationDetailsRowUpdated.organizationStage shouldBe OrganizationStage.LogoProvided
        organizationDetailsRowUpdated.logoOriginalFileName shouldBe Some(organizationLogoOriginalFileName)
        organizationDetailsRowUpdated.logoOriginalBucketKey.map(_.value) shouldBe
          Some(s"$expectedBucketKeyPrefix/original")
        organizationDetailsRowUpdated.logoNormalizedBucketKey.map(_.value) shouldBe
          Some(s"$expectedBucketKeyPrefix/normalized")
      }

      "fail with BadRequest when the file name header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationID = arbitrarySample[OrganizationID]
        val logoBytes      = ZStream.fromResource("assets/test-logo-1.jpeg").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.BadRequest](organizationID, None, logoBytes, Some(accessJwt.accessToken))
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.BadRequest
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            organizationID,
            Some(organizationLogoOriginalFileName),
            logoBytes,
            None,
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Unauthorized
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            organizationID,
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Unauthorized
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Unauthorized when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            organizationID,
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Unauthorized
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with InternalServerError when the uploaded file is not a supported image" in withContext { context =>
        import context.*

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoOriginalBucketKey = None,
            logoNormalizedBucketKey = None,
            logoOriginalFileName = None,
          )

        postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("malformed.png")
        val organizationID                   = organizationDetailsRow.organizationID
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            organizationID,
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.InternalServerError
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.InternalServerError()

        val organizationDetailsRowUpdated = postgresClient
          .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
          .zioValue
          .head

        organizationDetailsRowUpdated.organizationStage shouldBe organizationDetailsRowUpdated.organizationStage
        organizationDetailsRowUpdated.logoOriginalFileName shouldBe None
        organizationDetailsRowUpdated.logoOriginalBucketKey shouldBe None
        organizationDetailsRowUpdated.logoNormalizedBucketKey shouldBe None

        val expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

        s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }
    }
  }
}
