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
import java.time.temporal.ChronoUnit

class UserOtpRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userOtpTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userOtpTable).zioValue
    }
  }

  "UserOtpRepository" when {
    "upsertUserOtp" should {
      "successfully insert a new OTP for a user and return the inserted row" in new TestContext {
        val otpID = arbitrarySample[OtpID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(otpID.value)
            .once(),
        )

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
          otpID = otpID,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
      }

      "successfully upsert an existing OTP for a user and return the updated row" in new TestContext {
        val otpID             = arbitrarySample[OtpID]
        val instantNowUpdated = instantNow.plusSeconds(10)

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNowUpdated)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(otpID.value)
            .once(),
        )

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
        userOtpRowsAll.head.userID shouldBe userOtpRow.userID
        userOtpRowsAll.head.otpID should not be userOtpRow.otpID
        userOtpRowsAll.head.otp should not be userOtpRow.otp
        userOtpRowsAll.head.otpType shouldBe userOtpRow.otpType
        userOtpRowsAll.head.createdAt should not be userOtpRow.createdAt
        userOtpRowsAll.head.updatedAt should not be userOtpRow.updatedAt
        userOtpRowsAll.head.expiresAt should not be userOtpRow.expiresAt
        userOtpRowsAll.head shouldBe userOtpRowUpsert
        userOtpRowsAll.head shouldBe userOtpRow.copy(
          otpID = otpID,
          otp = otpUpdate,
          createdAt = CreatedAt(instantNowUpdated),
          expiresAt = expiresAtUpdate,
          updatedAt = UpdatedAt(instantNowUpdated),
        )
      }
    }

    "getUserOtp" should {
      "successfully get an existing OTP for a user by OTP ID, User ID and OTP type" in new TestContext {
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

      "return None when there is no OTP for the given OTP ID, User ID and OTP type" in new TestContext {
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
      "successfully retrieve an existing OTP for a user by OTP ID and OTP type" in new TestContext {
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

      "return None when there is no OTP for the given OTP ID and OTP type" in new TestContext {
        val otpID   = arbitrarySample[OtpID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptGet = userOtpRepository
          .getUserOtpByOtpID(otpID, otpType)
          .zioValue

        userOtpRowOptGet shouldBe None
      }
    }

    "getUserOtpByUserID" should {
      "successfully retrieve an existing OTP for a user by user ID and OTP type" in new TestContext {
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

      "return None when there is no OTP for the given user ID and OTP type" in new TestContext {
        val userID  = arbitrarySample[UserID]
        val otpType = arbitrarySample[OtpType]

        val userOtpRowOptGet = userOtpRepository
          .getUserOtpByUserID(userID, otpType)
          .zioValue

        userOtpRowOptGet shouldBe None
      }
    }

    "updateUserOtp" should {
      "successfully update an existing OTP for a user and return the updated row" in new TestContext {
        val instantNowUpdated = instantNow.plusSeconds(10)

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNowUpdated)
            .once()
        )

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
        userOtpRowsAll.head.userID shouldBe userOtpRow.userID
        userOtpRowsAll.head.otpID shouldBe userOtpRow.otpID
        userOtpRowsAll.head.otp shouldBe userOtpRow.otp
        userOtpRowsAll.head.otpType shouldBe userOtpRow.otpType
        userOtpRowsAll.head.createdAt shouldBe userOtpRow.createdAt
        userOtpRowsAll.head.updatedAt should not be userOtpRow.updatedAt
        userOtpRowsAll.head.expiresAt should not be userOtpRow.expiresAt
        userOtpRowsAll.head shouldBe userOtpRowUpdated
        userOtpRowsAll.head shouldBe userOtpRow.copy(
          expiresAt = expiresAtUpdate,
          updatedAt = UpdatedAt(instantNowUpdated),
        )
      }

      "fail to update a non existing user OTP" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

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
      "successfully delete an existing OTP for a user" in new TestContext {
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

      "successfully delete a non existin user OTP" in new TestContext {
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

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig = RepositoryConfig(
      schema = "local_schema",
      userOtpTable = "user_otp",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val userOtpQueries =
      ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val userOtpRepository = ZIO
      .service[UserOtpRepository]
      .provide(
        UserOtpRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(userOtpQueries),
        ZLayer.succeed(idGeneratorMock),
        ZLayer.succeed(timeProviderMock),
      )
      .zioValue
  }
}
