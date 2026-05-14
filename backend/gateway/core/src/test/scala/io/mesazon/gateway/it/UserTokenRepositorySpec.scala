package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.UserTokenRepository
import io.mesazon.gateway.repository.domain.UserTokenRow
import io.mesazon.gateway.repository.queries.UserTokenQueries
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, ZWordSpecBase}
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserTokenRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userTokenTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userTokenTable).zioValue
    }
  }

  "UserTokenRepository" when {
    "upsertUserToken" should {
      "successfully insert a user token" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val userTokenRow = arbitrarySample[UserTokenRow]

        userTokenRepository
          .upsertUserToken(
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
            tokenType = userTokenRow.tokenType,
            expiresAt = userTokenRow.expiresAt,
            tokenIDOptOld = None,
          )
          .zioValue shouldBe ()

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 1
        userTokensRowAll.head shouldBe userTokenRow
          .copy(createdAt = CreatedAt(instantNow))
      }

      "successfully upsert a user token when tokenIDOptOld is provided" in new TestContext {
        val instantNowUpdated = instantNow.plusSeconds(10)
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNowUpdated)
            .once()
        )

        val userTokenRowOld = arbitrarySample[UserTokenRow]
          .copy(
            createdAt = CreatedAt(instantNow.plusSeconds(2)),
            expiresAt = ExpiresAt(instantNow.plusSeconds(5)),
          )

        val userTokenRowNew = arbitrarySample[UserTokenRow]
          .copy(
            userID = userTokenRowOld.userID,
            tokenType = userTokenRowOld.tokenType,
          )

        userTokenRowOld should not be userTokenRowNew

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRowOld)
          )
          .zioValue

        userTokenRepository
          .upsertUserToken(
            tokenID = userTokenRowNew.tokenID,
            userID = userTokenRowNew.userID,
            tokenType = userTokenRowNew.tokenType,
            expiresAt = userTokenRowNew.expiresAt,
            tokenIDOptOld = Some(userTokenRowOld.tokenID),
          )
          .zioValue

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 1
        userTokensRowAll.head shouldBe userTokenRowNew
          .copy(createdAt = CreatedAt(instantNowUpdated))
      }

      "successfully upsert a user token when tokenIDOptOld is provided but the old token does not exist" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val tokenIDOld      = arbitrarySample[TokenID]
        val userTokenRowNew = arbitrarySample[UserTokenRow]

        userTokenRepository
          .upsertUserToken(
            tokenID = userTokenRowNew.tokenID,
            userID = userTokenRowNew.userID,
            tokenType = userTokenRowNew.tokenType,
            expiresAt = userTokenRowNew.expiresAt,
            tokenIDOptOld = Some(tokenIDOld),
          )
          .zioValue

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 1
        userTokensRowAll.head shouldBe userTokenRowNew
          .copy(createdAt = CreatedAt(instantNow))

      }

      "fail to re-insert the same token when not tokenIDOptOld is provided" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val userTokenRow = arbitrarySample[UserTokenRow]

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow)
          )
          .zioValue

        val serviceError = userTokenRepository
          .upsertUserToken(
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
            tokenType = userTokenRow.tokenType,
            expiresAt = userTokenRow.expiresAt,
            tokenIDOptOld = None,
          )
          .zioError

        serviceError.message shouldBe s"Failed to insert user token: [${userTokenRow.tokenID}], [${userTokenRow.tokenType}], [${userTokenRow.userID}], [${userTokenRow.expiresAt}]"
        serviceError.underlying.value shouldBe a[DbException]
      }

      "fail to upsert a user token when tokenIDOptOld is provided but the old token does not exist and another token with the same new tokenID already exists" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val tokenIDOld      = arbitrarySample[TokenID]
        val userTokenRowNew = arbitrarySample[UserTokenRow]

        val userTokenRowExisting = arbitrarySample[UserTokenRow]
          .copy(
            tokenID = userTokenRowNew.tokenID
          )

        userTokenRowNew should not be userTokenRowExisting

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRowExisting)
          )
          .zioValue

        val serviceError = userTokenRepository
          .upsertUserToken(
            tokenID = userTokenRowNew.tokenID,
            userID = userTokenRowNew.userID,
            tokenType = userTokenRowNew.tokenType,
            expiresAt = userTokenRowNew.expiresAt,
            tokenIDOptOld = Some(tokenIDOld),
          )
          .zioError

        serviceError.message shouldBe s"Failed to upsert user token: [$tokenIDOld], [${userTokenRowNew.tokenID}], [${userTokenRowNew.tokenType}], [${userTokenRowNew.userID}], [${userTokenRowNew.expiresAt}]"
        serviceError.underlying.value shouldBe a[DbException]
      }
    }

    "getUserToken" should {
      "successfully get a user token" in new TestContext {
        val userTokenRow = arbitrarySample[UserTokenRow]

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow)
          )
          .zioValue

        val userTokenRowOptGet = userTokenRepository
          .getUserToken(
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
            tokenType = userTokenRow.tokenType,
          )
          .zioValue

        userTokenRowOptGet shouldBe Some(userTokenRow)
      }

      "successfully return None when the user token does not exist" in new TestContext {
        val tokenID   = arbitrarySample[TokenID]
        val userID    = arbitrarySample[UserID]
        val tokenType = arbitrarySample[TokenType]

        val userTokenRowOptGet = userTokenRepository
          .getUserToken(
            tokenID = tokenID,
            userID = userID,
            tokenType = tokenType,
          )
          .zioValue

        userTokenRowOptGet shouldBe None
      }
    }

    "deleteUserToken" should {
      "successfully delete a user token" in new TestContext {
        val userTokenRow = arbitrarySample[UserTokenRow]

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow)
          )
          .zioValue

        userTokenRepository
          .deleteUserToken(
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
            tokenType = userTokenRow.tokenType,
          )
          .zioValue shouldBe ()

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 0
      }

      "successfully do nothing when trying to delete a non-existing user token" in new TestContext {
        val tokenID   = arbitrarySample[TokenID]
        val userID    = arbitrarySample[UserID]
        val tokenType = arbitrarySample[TokenType]

        userTokenRepository
          .deleteUserToken(
            tokenID = tokenID,
            userID = userID,
            tokenType = tokenType,
          )
          .zioValue shouldBe ()

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 0
      }

      "successfully delete only the specified user token when multiple tokens exist for the same user" in new TestContext {
        val userID = arbitrarySample[UserID]

        val userTokenRow1 = arbitrarySample[UserTokenRow].copy(userID = userID)
        val userTokenRow2 = arbitrarySample[UserTokenRow].copy(userID = userID)

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow1)
          )
          .zioValue

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow2)
          )
          .zioValue

        userTokenRepository
          .deleteUserToken(
            tokenID = userTokenRow1.tokenID,
            userID = userTokenRow1.userID,
            tokenType = userTokenRow1.tokenType,
          )
          .zioValue shouldBe ()

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 1
        userTokensRowAll.head shouldBe userTokenRow2
      }
    }

    "deleteAllUserTokens" should {
      "successfully delete all user tokens for a user" in new TestContext {
        val userID = arbitrarySample[UserID]

        val userTokenRow1 = arbitrarySample[UserTokenRow].copy(userID = userID)
        val userTokenRow2 = arbitrarySample[UserTokenRow].copy(userID = userID)

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow1)
          )
          .zioValue

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow2)
          )
          .zioValue

        userTokenRepository
          .deleteAllUserTokens(userID)
          .zioValue

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 0
      }

      "successfully do nothing when trying to delete all user tokens for a user that has no tokens" in new TestContext {
        val userID = arbitrarySample[UserID]

        userTokenRepository
          .deleteAllUserTokens(userID)
          .zioValue

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 0
      }

      "successfully delete only the specified user tokens when multiple users have tokens" in new TestContext {
        val userID1 = arbitrarySample[UserID]
        val userID2 = arbitrarySample[UserID]

        val userTokenRow1 = arbitrarySample[UserTokenRow].copy(userID = userID1)
        val userTokenRow2 = arbitrarySample[UserTokenRow].copy(userID = userID1)
        val userTokenRow3 = arbitrarySample[UserTokenRow].copy(userID = userID2)

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow1)
          )
          .zioValue

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow2)
          )
          .zioValue

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow3)
          )
          .zioValue

        userTokenRepository
          .deleteAllUserTokens(userID1)
          .zioValue

        val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokensRowAll should have size 1
        userTokensRowAll.head shouldBe userTokenRow3
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig = RepositoryConfig(
      schema = "local_schema",
      userTokenTable = "user_token",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val userTokenQueries =
      ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val timeProviderMock = mock[TimeProvider]

    val userTokenRepository = ZIO
      .service[UserTokenRepository]
      .provide(
        UserTokenRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(userTokenQueries),
        ZLayer.succeed(timeProviderMock),
      )
      .zioValue
  }
}
