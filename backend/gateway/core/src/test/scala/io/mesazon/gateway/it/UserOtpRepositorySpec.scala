package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.UserOtpRepository
import io.mesazon.gateway.repository.domain.UserOtpRow
import io.mesazon.gateway.repository.queries.UserOtpQueries
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class UserOtpRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userOtpTable = "user_otp",
  )

  case class Context(
      postgresClient: PostgreSQLTestClient,
      userOtpQueries: UserOtpQueries,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue
    val userOtpQueries =
      ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    f(Context(postgreSQLTestClient, userOtpQueries))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userOtpTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userOtpTable).zioValue
    }
  }

  "UserOtpRepository" when {
    "upsertUserOtp" should {
      "successfully insert a new OTP for a user and return the inserted row" in withContext { context =>
        import context.*

        val now      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow = Clock.fixed(now, ZoneOffset.UTC)

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        val userOtpRowInserted = userOtpRepository
          .upsertUserOtp(
            userID = userOtpRow.userID,
            otp = userOtpRow.otp,
            otpType = userOtpRow.otpType,
            expiresAt = userOtpRow.expiresAt,
          )
          .zioValue

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll should contain theSameElementsAs List(userOtpRowInserted)
      }

      "successfully upsert an existing OTP for a user and return the updated row" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val expiresAtUpdate = ExpiresAt(userOtpRow.expiresAt.value.plusSeconds(1))
        val otpUpdate       = arbitrarySample[Otp]

        val userOtpRowUpdated = userOtpRepository
          .upsertUserOtp(
            userID = userOtpRow.userID,
            otp = otpUpdate,
            otpType = userOtpRow.otpType,
            expiresAt = expiresAtUpdate,
          )
          .zioValue

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll should contain theSameElementsAs List(
          userOtpRow.copy(
            otpID = OtpID.assume("1"),
            otp = otpUpdate,
            createdAt = CreatedAt(instantNow),
            expiresAt = expiresAtUpdate,
            updatedAt = UpdatedAt(instantNow),
          )
        )
        userOtpRowsAll should contain theSameElementsAs List(
          userOtpRowUpdated
        )
      }
    }

    "getUserOtp" should {
      "successfully retrieve an existing OTP for a user by OTP ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val userOtpRowOptRetrieved = userOtpRepository
          .getUserOtp(otpID = userOtpRow.otpID, otpType = userOtpRow.otpType)
          .zioValue

        userOtpRowOptRetrieved shouldBe Some(userOtpRow)
      }

      "return None when there is no OTP for the given OTP ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val otpID   = arbitrarySample[OtpID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptRetrieved = userOtpRepository
          .getUserOtp(otpID, otpType)
          .zioValue

        userOtpRowOptRetrieved shouldBe None
      }
    }

    "getUserOtpByUserID" should {
      "successfully retrieve an existing OTP for a user by user ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val userOtpRowOptRetrieved = userOtpRepository
          .getUserOtpByUserID(userID = userOtpRow.userID, otpType = userOtpRow.otpType)
          .zioValue

        userOtpRowOptRetrieved shouldBe Some(userOtpRow)
      }

      "return None when there is no OTP for the given user ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userID  = arbitrarySample[UserID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptRetrieved = userOtpRepository
          .getUserOtpByUserID(userID, otpType)
          .zioValue

        userOtpRowOptRetrieved shouldBe None
      }
    }

    "updateUserOtp" should {
      "successfully update an existing OTP for a user and return the updated row" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow   = Clock.fixed(instantNow, ZoneOffset.UTC)

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val expiresAtUpdate = ExpiresAt(userOtpRow.expiresAt.value.plusSeconds(1))

        val userOtpRowUpdated = userOtpRepository
          .updateUserOtp(
            otpID = userOtpRow.otpID,
            userID = userOtpRow.userID,
            otpType = userOtpRow.otpType,
            expiresAtUpdate = expiresAtUpdate,
          )
          .zioValue

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll should contain theSameElementsAs List(
          userOtpRow.copy(
            expiresAt = expiresAtUpdate,
            updatedAt = UpdatedAt(instantNow),
          )
        )
        userOtpRowsAll should contain theSameElementsAs List(
          userOtpRowUpdated
        )
      }

      "fail to update a non existing user OTP" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        val expiresAtUpdate = ExpiresAt(userOtpRow.expiresAt.value.plusSeconds(1))

        val serviceError = userOtpRepository
          .updateUserOtp(
            otpID = userOtpRow.otpID,
            userID = userOtpRow.userID,
            otpType = userOtpRow.otpType,
            expiresAtUpdate = expiresAtUpdate,
          )
          .zioError

        serviceError.message shouldBe s"Failed to update user OTP: [${userOtpRow.otpID}], [${userOtpRow.userID}], [${userOtpRow.otpType}], [$expiresAtUpdate]"
        serviceError.underlying.value shouldBe a[DbException]
      }
    }

    "deleteUserOtp" should {
      "successfully delete an existing OTP for a user" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        userOtpRepository
          .deleteUserOtp(
            otpID = userOtpRow.otpID,
            userID = userOtpRow.userID,
            otpType = userOtpRow.otpType,
          )
          .zioValue

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll shouldBe empty
      }

      "successfully delete a non existin user OTP" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        userOtpRepository
          .deleteUserOtp(
            otpID = userOtpRow.otpID,
            userID = userOtpRow.userID,
            otpType = userOtpRow.otpType,
          )
          .zioValue

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll shouldBe empty
      }
    }
  }
}
