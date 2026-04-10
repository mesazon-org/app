package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.UserTokenRepository
import io.mesazon.gateway.repository.domain.UserTokenRow
import io.mesazon.gateway.repository.queries.UserTokenQueries
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, ZWordSpecBase}
import zio.*

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
        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            ZLayer.succeed(userTokenQueries),
            Mocks.timeProviderLive(clockNow),
          )
          .zioValue

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
        userTokensRowAll should contain theSameElementsAs List(userTokenRow.copy(createdAt = CreatedAt(instantNow)))
      }

      "successfully upsert a user token when tokenIDOptOld is provided" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            ZLayer.succeed(userTokenQueries),
            Mocks.timeProviderLive(clockNow),
          )
          .zioValue

        val userTokenRowOld = arbitrarySample[UserTokenRow]
          .copy(createdAt = CreatedAt(instantNow.minusSeconds(10)), expiresAt = ExpiresAt(instantNow.minusSeconds(5)))
        val userTokenRowNew = arbitrarySample[UserTokenRow]
          .copy(
            userID = userTokenRowOld.userID,
            tokenType = userTokenRowOld.tokenType,
            createdAt = CreatedAt(instantNow),
            expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
          )

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
        userTokensRowAll should contain theSameElementsAs List(
          userTokenRowNew
        )
      }

      "successfully upsert a user token when tokenIDOptOld is provided but the old token does not exist" in withContext {
        context =>
          import context.*

          val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

          val userTokenRepository = ZIO
            .service[UserTokenRepository]
            .provide(
              UserTokenRepository.live,
              ZLayer.succeed(postgresClient.database),
              ZLayer.succeed(userTokenQueries),
              Mocks.timeProviderLive(clockNow),
            )
            .zioValue

          val tokenIDOld      = arbitrarySample[TokenID]
          val userTokenRowNew = arbitrarySample[UserTokenRow]
            .copy(createdAt = CreatedAt(instantNow), expiresAt = ExpiresAt(instantNow.plusSeconds(10)))

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
          userTokensRowAll should contain theSameElementsAs List(
            userTokenRowNew
          )
      }

      "fail to re-insert the same token when not tokenIDOptOld is provided" in withContext { context =>
        import context.*

        val instantNow          = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow            = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            ZLayer.succeed(userTokenQueries),
            Mocks.timeProviderLive(clockNow),
          )
          .zioValue

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

      "fail to upsert a user token when tokenIDOptOld is provided but the old token does not exist and another token with the same new tokenID already exists" in withContext {
        context =>
          import context.*

          val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

          val userTokenRepository = ZIO
            .service[UserTokenRepository]
            .provide(
              UserTokenRepository.live,
              ZLayer.succeed(postgresClient.database),
              ZLayer.succeed(userTokenQueries),
              Mocks.timeProviderLive(clockNow),
            )
            .zioValue

          val tokenIDOld      = arbitrarySample[TokenID]
          val userTokenRowNew = arbitrarySample[UserTokenRow]
            .copy(createdAt = CreatedAt(instantNow), expiresAt = ExpiresAt(instantNow.plusSeconds(10)))
          val userTokenRowExisting = arbitrarySample[UserTokenRow]
            .copy(
              tokenID = userTokenRowNew.tokenID,
              createdAt = CreatedAt(instantNow),
              expiresAt = ExpiresAt(instantNow.plusSeconds(20)),
            )

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
      "successfully get a user token" in withContext { context =>
        import context.*

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userTokenQueries),
          )
          .zioValue

        val userTokenRow = arbitrarySample[UserTokenRow]

        postgresClient
          .executeQuery(
            userTokenQueries.insertUserToken(userTokenRow)
          )
          .zioValue

        val userTokenRowOptRetrieved = userTokenRepository
          .getUserToken(
            tokenID = userTokenRow.tokenID,
            userID = userTokenRow.userID,
            tokenType = userTokenRow.tokenType,
          )
          .zioValue

        userTokenRowOptRetrieved shouldBe Some(userTokenRow)
      }

      "successfully return None when the user token does not exist" in withContext { context =>
        import context.*

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userTokenQueries),
          )
          .zioValue

        val tokenID   = arbitrarySample[TokenID]
        val userID    = arbitrarySample[UserID]
        val tokenType = arbitrarySample[TokenType]

        val userTokenRowOptRetrieved = userTokenRepository
          .getUserToken(
            tokenID = tokenID,
            userID = userID,
            tokenType = tokenType,
          )
          .zioValue

        userTokenRowOptRetrieved shouldBe None
      }
    }

    "deleteUserToken" should {
      "successfully delete a user token" in withContext { context =>
        import context.*

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userTokenQueries),
          )
          .zioValue

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

      "successfully do nothing when trying to delete a non-existing user token" in withContext { context =>
        import context.*

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userTokenQueries),
          )
          .zioValue

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

      "successfully delete only the specified user token when multiple tokens exist for the same user" in withContext {
        context =>
          import context.*

          val userTokenRepository = ZIO
            .service[UserTokenRepository]
            .provide(
              UserTokenRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
              ZLayer.succeed(userTokenQueries),
            )
            .zioValue

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
          userTokensRowAll should contain theSameElementsAs List(userTokenRow2)
      }
    }

    "deleteAllUserTokens" should {
      "successfully delete all user tokens for a user" in withContext { context =>
        import context.*

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userTokenQueries),
          )
          .zioValue

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

      "successfully do nothing when trying to delete all user tokens for a user that has no tokens" in withContext {
        context =>
          import context.*

          val userTokenRepository = ZIO
            .service[UserTokenRepository]
            .provide(
              UserTokenRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
              ZLayer.succeed(userTokenQueries),
            )
            .zioValue

          val userID = arbitrarySample[UserID]

          userTokenRepository
            .deleteAllUserTokens(userID)
            .zioValue

          val userTokensRowAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

          userTokensRowAll should have size 0
      }

      "successfully delete only the specified user tokens when multiple users have tokens" in withContext { context =>
        import context.*

        val userTokenRepository = ZIO
          .service[UserTokenRepository]
          .provide(
            UserTokenRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            ZLayer.succeed(userTokenQueries),
          )
          .zioValue

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
        userTokensRowAll should contain theSameElementsAs List(userTokenRow3)
      }
    }
  }
}
