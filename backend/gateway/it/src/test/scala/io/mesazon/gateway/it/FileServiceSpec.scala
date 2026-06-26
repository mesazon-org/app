package io.mesazon.gateway.it

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.*
import sttp.model.*
import zio.*

class FileServiceSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices =
    GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices ++ S3TestClient.ExposedServices

  case class Context(
      gatewayClient: GatewayClient,
      postgresClient: PostgreSQLTestClient,
      s3TestClient: S3TestClient,
      repositoryConfig: RepositoryConfig,
      organizationDetailsQueries: OrganizationDetailsQueries,
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
      jwtService <- ZIO
        .service[JwtService]
        .provide(
          JwtService.live,
          JwtConfig.live,
          appNameLive,
          IDGenerator.liveUUIDv7,
          TimeProvider.liveSystemUTC,
        )
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      s3TestClient,
      repositoryConfig,
      organizationDetailsQueries,
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
  }

  "File Service API" when {
    "/upload/organization/logo/{organizationID}" should {
      "successfully upload organization logo" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(organizationID = organizationID)

        postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow)).zioValue

      }
    }
  }
}
