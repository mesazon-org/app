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
//
//class UserCredentialsRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {
//
//  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"
//
//  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices
//
//  val repositoryConfig = RepositoryConfig(
//    schema = "local_schema",
//    userCredentialsTable = "user_credentials",
//  )
//
//  case class Context(
//      postgresClient: PostgreSQLTestClient,
//      userCredentialsQueries: UserCredentialsQueries,
//  )
//
//  def withContext[A](f: Context => A): A = withContainers { container =>
//    val config               = PostgreSQLTestClientConfig.from(container)
//    val postgreSQLTestClient = ZIO
//      .service[PostgreSQLTestClient]
//      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
//      .zioValue
//    val userCredentialsQueries =
//      ZIO
//        .service[UserCredentialsQueries]
//        .provide(UserCredentialsQueries.live, ZLayer.succeed(repositoryConfig))
//        .zioValue
//
//    f(Context(postgreSQLTestClient, userCredentialsQueries))
//  }
//
//  override def beforeAll(): Unit = withContext { context =>
//    import context.*
//
//    super.beforeAll()
//
//    eventually {
//      postgresClient
//        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userCredentialsTable)
//        .zioValue shouldBe true
//    }
//  }
//
//  override def beforeEach(): Unit = withContext { context =>
//    import context.*
//
//    super.beforeEach()
//
//    eventually {
//      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userCredentialsTable).zioValue
//    }
//  }
//
//  "UserCredentialsRepository" when {
//    "insertUserCredentials" should {
//      "successfully insert new credentials for a user" in withContext { context =>
//        import context.*
//
//        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
//        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)
//
//        val userCredentialsRepository = ZIO
//          .service[UserCredentialsRepository]
//          .provide(
//            UserCredentialsRepository.live,
//            ZLayer.succeed(postgresClient.database),
//            Mocks.timeProviderLive(clockNow),
//            ZLayer.succeed(userCredentialsQueries),
//          )
//          .zioValue
//
//        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
//
//        userCredentialsRepository
//          .insertUserCredentials(
//            userID = userCredentialsRow.userID,
//            passwordHash = userCredentialsRow.passwordHash,
//          )
//          .zioValue
//
//        val userCredentialsRowsAll =
//          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue
//
//        userCredentialsRowsAll should have size 1
//        userCredentialsRowsAll should contain theSameElementsAs List(
//          userCredentialsRow.copy(
//            createdAt = CreatedAt(instantNow),
//            updatedAt = UpdatedAt(instantNow),
//          )
//        )
//      }
//
//      "fail to insert credentials for already existing user" in withContext { context =>
//        import context.*
//
//        val userCredentialsRepository = ZIO
//          .service[UserCredentialsRepository]
//          .provide(
//            UserCredentialsRepository.live,
//            ZLayer.succeed(postgresClient.database),
//            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
//            ZLayer.succeed(userCredentialsQueries),
//          )
//          .zioValue
//
//        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
//
//        postgresClient
//          .executeQuery(
//            userCredentialsQueries.insertUserCredentials(userCredentialsRow)
//          )
//          .zioValue
//
//        val serviceError = userCredentialsRepository
//          .insertUserCredentials(
//            userID = userCredentialsRow.userID,
//            passwordHash = userCredentialsRow.passwordHash,
//          )
//          .zioError
//
//        serviceError.message shouldBe s"Failed to insert user credentials for user ID: [${userCredentialsRow.userID}]"
//        serviceError.underlying.value shouldBe a[DbException]
//      }
//    }
//
//    "updateUserCredentials" should {
//      "successfully update existing credentials for a user" in withContext { context =>
//        import context.*
//
//        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
//        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)
//
//        val userCredentialsRepository = ZIO
//          .service[UserCredentialsRepository]
//          .provide(
//            UserCredentialsRepository.live,
//            ZLayer.succeed(postgresClient.database),
//            Mocks.timeProviderLive(clockNow),
//            ZLayer.succeed(userCredentialsQueries),
//          )
//          .zioValue
//
//        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
//
//        postgresClient
//          .executeQuery(
//            userCredentialsQueries.insertUserCredentials(userCredentialsRow)
//          )
//          .zioValue
//
//        val passwordHashUpdate = arbitrarySample[PasswordHash]
//
//        userCredentialsRepository
//          .updateUserCredentials(
//            userID = userCredentialsRow.userID,
//            passwordHashUpdate = passwordHashUpdate,
//          )
//          .zioValue
//
//        val userCredentialsRowsAll =
//          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue
//
//        userCredentialsRowsAll should have size 1
//        userCredentialsRowsAll should contain theSameElementsAs List(
//          userCredentialsRow.copy(
//            passwordHash = passwordHashUpdate,
//            updatedAt = UpdatedAt(instantNow),
//          )
//        )
//      }
//
//      "successfully update credentials for a not existing user" in withContext { context =>
//        import context.*
//
//        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
//        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)
//
//        val userCredentialsRepository = ZIO
//          .service[UserCredentialsRepository]
//          .provide(
//            UserCredentialsRepository.live,
//            ZLayer.succeed(postgresClient.database),
//            Mocks.timeProviderLive(clockNow),
//            ZLayer.succeed(userCredentialsQueries),
//          )
//          .zioValue
//
//        val userID             = arbitrarySample[UserID]
//        val passwordHashUpdate = arbitrarySample[PasswordHash]
//
//        userCredentialsRepository
//          .updateUserCredentials(
//            userID = userID,
//            passwordHashUpdate = passwordHashUpdate,
//          )
//          .zioValue
//
//        val userCredentialsRowsAll =
//          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue
//
//        userCredentialsRowsAll shouldBe empty
//      }
//    }
//
//    "getUserCredentials" should {
//      "successfully retrieve existing credentials for a user by user ID" in withContext { context =>
//        import context.*
//
//        val userCredentialsRepository = ZIO
//          .service[UserCredentialsRepository]
//          .provide(
//            UserCredentialsRepository.live,
//            ZLayer.succeed(postgresClient.database),
//            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
//            ZLayer.succeed(userCredentialsQueries),
//          )
//          .zioValue
//
//        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
//
//        postgresClient
//          .executeQuery(
//            userCredentialsQueries.insertUserCredentials(userCredentialsRow)
//          )
//          .zioValue
//
//        val userCredentialsRowOptRetrieved = userCredentialsRepository
//          .getUserCredentials(userID = userCredentialsRow.userID)
//          .zioValue
//
//        userCredentialsRowOptRetrieved shouldBe Some(userCredentialsRow)
//      }
//
//      "return None when there are no credentials for the given user ID" in withContext { context =>
//        import context.*
//
//        val userCredentialsRepository = ZIO
//          .service[UserCredentialsRepository]
//          .provide(
//            UserCredentialsRepository.live,
//            ZLayer.succeed(postgresClient.database),
//            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
//            ZLayer.succeed(userCredentialsQueries),
//          )
//          .zioValue
//
//        val userID = arbitrarySample[UserID]
//
//        val userCredentialsRowOptRetrieved = userCredentialsRepository
//          .getUserCredentials(userID)
//          .zioValue
//
//        userCredentialsRowOptRetrieved shouldBe None
//      }
//    }
//  }
//}
