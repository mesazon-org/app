package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.CreatedAt
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.UserTokenRepository
import io.mesazon.gateway.repository.domain.UserTokenRow
import io.mesazon.gateway.repository.queries.UserTokenQueries
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, ZWordSpecBase}
import zio.{ZIO, ZLayer}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class UserTokenRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userTokenTable = "user_token",
  )

  case class Context(
      postgresClient: PostgreSQLTestClient,
      userTokenQueries: UserTokenQueries,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue
    val userTokenQueries =
      ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    f(Context(postgreSQLTestClient, userTokenQueries))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userTokenTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userTokenTable).zioValue
    }
  }

  "UserTokenRepository" when {
    "upsertUserToken" should {
      "successfully insert a user token" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow   = Clock.fixed(now, ZoneOffset.UTC)

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(userTokenQueries),
            Mocks.timeProviderLive(clockNow),
          )
          .zioValue

        val userTokenRow = arbitrarySample[UserTokenRow]

        userTokenRepository
          .upsertUserToken(
            tokenIDOptOld = None,
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
            tokenType = userTokenRow.tokenType,
            expiresAt = userTokenRow.expiresAt,
          )
          .zioValue shouldBe ()

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokens).zioValue

        userTokensRowAll should have size 1
        userTokensRowAll should contain theSameElementsAs List(userTokenRow.copy(createdAt = CreatedAt(instantNow)))
      }
    }
  }
}
