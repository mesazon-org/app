package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.UserCredentialsRepository
import io.mesazon.gateway.repository.domain.UserCredentialsRow
import io.mesazon.gateway.repository.queries.UserCredentialsQueries
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserCredentialsRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userCredentialsTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userCredentialsTable).zioValue
    }
  }

  "UserCredentialsRepository" when {
    "insertUserCredentials" should {
      "successfully insert new credentials for a user" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val userCredentialsRow = arbitrarySample[UserCredentialsRow]

        userCredentialsRepository
          .insertUserCredentials(
            userID = userCredentialsRow.userID,
            passwordHash = userCredentialsRow.passwordHash,
          )
          .zioValue

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe
          userCredentialsRow.copy(
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )
      }

      "fail to insert credentials for already existing user" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val userCredentialsRow = arbitrarySample[UserCredentialsRow]

        postgresClient
          .executeQuery(
            userCredentialsQueries.insertUserCredentials(userCredentialsRow)
          )
          .zioValue

        val serviceError = userCredentialsRepository
          .insertUserCredentials(
            userID = userCredentialsRow.userID,
            passwordHash = userCredentialsRow.passwordHash,
          )
          .zioError

        serviceError.message shouldBe s"Failed to insert user credentials for user ID: [${userCredentialsRow.userID}]"
        serviceError.underlying.value shouldBe a[DbException]
      }
    }

    "updateUserCredentials" should {
      "successfully update existing credentials for a user" in new TestContext {
        val instantNowUpdate = instantNow.plusSeconds(10)
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNowUpdate)
            .once()
        )

        val userCredentialsRow = arbitrarySample[UserCredentialsRow]

        postgresClient
          .executeQuery(
            userCredentialsQueries.insertUserCredentials(userCredentialsRow)
          )
          .zioValue

        val passwordHashUpdate = arbitrarySample[PasswordHash]

        userCredentialsRepository
          .updateUserCredentials(
            userID = userCredentialsRow.userID,
            passwordHashUpdate = passwordHashUpdate,
          )
          .zioValue

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head.userID shouldBe userCredentialsRow.userID
        userCredentialsRowsAll.head.passwordHash should not be userCredentialsRow.passwordHash
        userCredentialsRowsAll.head.createdAt shouldBe userCredentialsRow.createdAt
        userCredentialsRowsAll.head.updatedAt should not be userCredentialsRow.updatedAt
        userCredentialsRowsAll.head shouldBe userCredentialsRow.copy(
          passwordHash = passwordHashUpdate,
          updatedAt = UpdatedAt(instantNowUpdate),
        )
      }

      "successfully update credentials for a not existing user" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val userID             = arbitrarySample[UserID]
        val passwordHashUpdate = arbitrarySample[PasswordHash]

        userCredentialsRepository
          .updateUserCredentials(
            userID = userID,
            passwordHashUpdate = passwordHashUpdate,
          )
          .zioValue

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll shouldBe empty
      }
    }

    "getUserCredentials" should {
      "successfully get existing credentials for a user by user ID" in new TestContext {
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]

        postgresClient
          .executeQuery(
            userCredentialsQueries.insertUserCredentials(userCredentialsRow)
          )
          .zioValue

        val userCredentialsRowOptGet = userCredentialsRepository
          .getUserCredentials(userID = userCredentialsRow.userID)
          .zioValue

        userCredentialsRowOptGet shouldBe Some(userCredentialsRow)
      }

      "return None when there are no credentials for the given user ID" in new TestContext {
        val userID = arbitrarySample[UserID]

        val userCredentialsRowOptGet = userCredentialsRepository
          .getUserCredentials(userID)
          .zioValue

        userCredentialsRowOptGet shouldBe None
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig = RepositoryConfig(
      schema = "local_schema",
      userCredentialsTable = "user_credentials",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val userCredentialsQueries =
      ZIO
        .service[UserCredentialsQueries]
        .provide(UserCredentialsQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val timeProviderMock = mock[TimeProvider]

    def userCredentialsRepository = ZIO
      .service[UserCredentialsRepository]
      .provide(
        UserCredentialsRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(userCredentialsQueries),
        ZLayer.succeed(timeProviderMock),
      )
      .zioValue
  }
}
