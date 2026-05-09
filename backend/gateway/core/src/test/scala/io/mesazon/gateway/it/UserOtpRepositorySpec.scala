package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.UserOtpRepository
import io.mesazon.gateway.repository.domain.UserOtpRow
import io.mesazon.gateway.repository.queries.UserOtpQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant

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

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        val userOtpRowUpsert = userOtpRepository
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
        userOtpRowsAll.head shouldBe userOtpRowUpsert
        userOtpRowsAll.head shouldBe userOtpRow.copy(
          otpID = userOtpRowsAll.head.otpID,
          createdAt = userOtpRowsAll.head.createdAt,
          updatedAt = userOtpRowsAll.head.updatedAt,
        )
      }

      "successfully upsert an existing OTP for a user and return the updated row" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
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

        val userOtpRowUpsert = userOtpRepository
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
        userOtpRowsAll.head.updatedAt should not be userOtpRow.updatedAt
        userOtpRowsAll.head shouldBe userOtpRowUpsert
        userOtpRowsAll.head shouldBe userOtpRow.copy(
          otpID = userOtpRowsAll.head.otpID,
          otp = otpUpdate,
          createdAt = userOtpRowsAll.head.createdAt,
          expiresAt = expiresAtUpdate,
          updatedAt = userOtpRowsAll.head.updatedAt,
        )
      }
    }

    "getUserOtp" should {
      "successfully get an existing OTP for a user by OTP ID, User ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val userOtpRowOptGet = userOtpRepository
          .getUserOtp(userOtpRow.otpID, userOtpRow.userID, userOtpRow.otpType)
          .zioValue

        userOtpRowOptGet shouldBe Some(userOtpRow)
      }

      "return None when there is no OTP for the given OTP ID, User ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val otpID   = arbitrarySample[OtpID]
        val userID  = arbitrarySample[UserID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptGet = userOtpRepository
          .getUserOtp(otpID, userID, otpType)
          .zioValue

        userOtpRowOptGet shouldBe None
      }
    }

    "getUserOtpByOtpID" should {
      "successfully retrieve an existing OTP for a user by OTP ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val userOtpRowOptGet = userOtpRepository
          .getUserOtpByOtpID(otpID = userOtpRow.otpID, otpType = userOtpRow.otpType)
          .zioValue

        userOtpRowOptGet shouldBe Some(userOtpRow)
      }

      "return None when there is no OTP for the given OTP ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val otpID   = arbitrarySample[OtpID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptGet = userOtpRepository
          .getUserOtpByOtpID(otpID, otpType)
          .zioValue

        userOtpRowOptGet shouldBe None
      }
    }

    "getUserOtpByUserID" should {
      "successfully retrieve an existing OTP for a user by user ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userOtpRow = arbitrarySample[UserOtpRow]

        postgresClient
          .executeQuery(
            userOtpQueries.insertUserOtp(userOtpRow)
          )
          .zioValue

        val userOtpRowOptGet = userOtpRepository
          .getUserOtpByUserID(userID = userOtpRow.userID, otpType = userOtpRow.otpType)
          .zioValue

        userOtpRowOptGet shouldBe Some(userOtpRow)
      }

      "return None when there is no OTP for the given user ID and OTP type" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
            ZLayer.succeed(userOtpQueries),
          )
          .zioValue

        val userID  = arbitrarySample[UserID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptGet = userOtpRepository
          .getUserOtpByUserID(userID, otpType)
          .zioValue

        userOtpRowOptGet shouldBe None
      }
    }

    "updateUserOtp" should {
      "successfully update an existing OTP for a user and return the updated row" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
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
        userOtpRowsAll.head shouldBe
          userOtpRow.copy(
            expiresAt = expiresAtUpdate,
            updatedAt = userOtpRowsAll.head.updatedAt,
          )

        userOtpRowsAll.head shouldBe userOtpRowUpdated
      }

      "fail to update a non existing user OTP" in withContext { context =>
        import context.*

        val userOtpRepository = ZIO
          .service[UserOtpRepository]
          .provide(
            UserOtpRepository.live,
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
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
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
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
            postgresClient.databaseLive,
            TimeProvider.liveSystemUTC,
            IDGenerator.liveUUIDv7,
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
