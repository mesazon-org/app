package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserActionAttemptRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userActionAttemptTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userActionAttemptTable).zioValue
    }
  }

  "UserActionAttemptRepository" when {
    "getAndIncreaseUserActionAttempt" should {
      "successfully insert new action attempt for a user and action type" in new TestContext {
        val userID            = arbitrarySample[UserID]
        val actionAttemptType = arbitrarySample[ActionAttemptType]

        val actionAttemptID = arbitrarySample[ActionAttemptID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(actionAttemptID.value)
            .once(),
        )

        val userActionAttemptRowGet = userActionAttemptRepository
          .getAndIncreaseUserActionAttempt(userID, actionAttemptType)
          .zioValue

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 1
        userActionAttemptRowsAll.head shouldBe userActionAttemptRowGet
        userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
          actionAttemptID = actionAttemptID,
          userID = userID,
          actionAttemptType = actionAttemptType,
          attempts = Attempts.assume(1),
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
      }

      "successfully get old record and increase existing action attempt count for a user and action type" in new TestContext {
        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
          .copy(
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )

        val actionAttemptID2 = arbitrarySample[ActionAttemptID]
        val instantNowUpdate = instantNow.plusSeconds(10)

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNowUpdate)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(actionAttemptID2.value)
            .once(),
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow)
          )
          .zioValue

        val userActionAttemptRowGet = userActionAttemptRepository
          .getAndIncreaseUserActionAttempt(userActionAttemptRow.userID, userActionAttemptRow.actionAttemptType)
          .zioValue

        userActionAttemptRowGet shouldBe userActionAttemptRow

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 1

        userActionAttemptRowsAll.head.actionAttemptID shouldBe userActionAttemptRow.actionAttemptID
        userActionAttemptRowsAll.head.userID shouldBe userActionAttemptRow.userID
        userActionAttemptRowsAll.head.actionAttemptType shouldBe userActionAttemptRow.actionAttemptType
        userActionAttemptRowsAll.head.attempts should not be userActionAttemptRow.attempts
        userActionAttemptRowsAll.head.createdAt shouldBe userActionAttemptRow.createdAt
        userActionAttemptRowsAll.head.updatedAt should not be userActionAttemptRow.updatedAt

        userActionAttemptRowsAll.head shouldBe userActionAttemptRow.copy(
          attempts = Attempts.assume(userActionAttemptRow.attempts.value + 1),
          updatedAt = UpdatedAt(instantNowUpdate),
        )
      }
    }

    "deleteUserActionAttempt" should {
      "successfully delete existing action attempt for a user and action type" in new TestContext {
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

      "successfully delete non existing action attempt for a user and action type" in new TestContext {
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

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig = RepositoryConfig(
      schema = "local_schema",
      userActionAttemptTable = "user_action_attempt",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue
    val userActionAttemptQueries =
      ZIO
        .service[UserActionAttemptQueries]
        .provide(UserActionAttemptQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    def userActionAttemptRepository = ZIO
      .service[UserActionAttemptRepository]
      .provide(
        UserActionAttemptRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(userActionAttemptQueries),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
      )
      .zioValue
  }
}
