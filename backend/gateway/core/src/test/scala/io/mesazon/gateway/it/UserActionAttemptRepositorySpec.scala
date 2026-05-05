package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class UserActionAttemptRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userActionAttemptTable = "user_action_attempt",
  )

  case class Context(
      postgresClient: PostgreSQLTestClient,
      userActionAttemptQueries: UserActionAttemptQueries,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue
    val userActionAttemptQueries =
      ZIO
        .service[UserActionAttemptQueries]
        .provide(UserActionAttemptQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    f(Context(postgreSQLTestClient, userActionAttemptQueries))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userActionAttemptTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userActionAttemptTable).zioValue
    }
  }

  "UserActionAttemptRepository" when {
    "getAndIncreaseUserActionAttempt" should {
      "successfully insert new action attempt for a user and action type" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

        val userActionAttemptRepository = ZIO
          .service[UserActionAttemptRepository]
          .provide(
            UserActionAttemptRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            ZLayer.succeed(userActionAttemptQueries),
            Mocks.idGeneratorLive,
          )
          .zioValue

        val userID            = arbitrarySample[UserID]
        val actionAttemptType = arbitrarySample[ActionAttemptType]

        val userActionAttemptRowRetrieved = userActionAttemptRepository
          .getAndIncreaseUserActionAttempt(userID, actionAttemptType)
          .zioValue

        val userActionAttemptRowExpected = UserActionAttemptRow(
          actionAttemptID = ActionAttemptID.assume("1"),
          userID = userID,
          actionAttemptType = actionAttemptType,
          attempts = Attempts.assume(1),
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        userActionAttemptRowRetrieved shouldBe userActionAttemptRowExpected

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 1
        userActionAttemptRowsAll should contain theSameElementsAs List(userActionAttemptRowExpected)
      }

      "successfully get old record and increase existing action attempt count for a user and action type" in withContext {
        context =>
          import context.*

          val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

          val userActionAttemptRepository = ZIO
            .service[UserActionAttemptRepository]
            .provide(
              UserActionAttemptRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              ZLayer.succeed(userActionAttemptQueries),
              Mocks.idGeneratorLive,
            )
            .zioValue

          val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]

          postgresClient
            .executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow)
            )
            .zioValue

          val userActionAttemptRowRetrieved = userActionAttemptRepository
            .getAndIncreaseUserActionAttempt(userActionAttemptRow.userID, userActionAttemptRow.actionAttemptType)
            .zioValue

          userActionAttemptRowRetrieved shouldBe userActionAttemptRow

          val userActionAttemptRowExpected = userActionAttemptRow.copy(
            attempts = Attempts.assume(userActionAttemptRow.attempts.value + 1),
            updatedAt = UpdatedAt(instantNow),
          )

          val userActionAttemptRowsAll =
            postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

          userActionAttemptRowsAll should have size 1
          userActionAttemptRowsAll should contain theSameElementsAs List(userActionAttemptRowExpected)
      }
    }

    "deleteUserActionAttempt" should {
      "successfully delete existing action attempt for a user and action type" in withContext { context =>
        import context.*

        val userActionAttemptRepository = ZIO
          .service[UserActionAttemptRepository]
          .provide(
            UserActionAttemptRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userActionAttemptQueries),
            Mocks.idGeneratorLive,
          )
          .zioValue

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow)
          )
          .zioValue

        userActionAttemptRepository
          .deleteUserActionAttempt(userActionAttemptRow.userID, userActionAttemptRow.actionAttemptType)
          .zioValue

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty
      }

      "successfully delete non existing action attempt for a user and action type" in withContext { context =>
        import context.*

        val userActionAttemptRepository = ZIO
          .service[UserActionAttemptRepository]
          .provide(
            UserActionAttemptRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userActionAttemptQueries),
            Mocks.idGeneratorLive,
          )
          .zioValue

        val userID            = arbitrarySample[UserID]
        val actionAttemptType = arbitrarySample[ActionAttemptType]

        userActionAttemptRepository
          .deleteUserActionAttempt(userID, actionAttemptType)
          .zioValue

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty
      }
    }
  }
}
