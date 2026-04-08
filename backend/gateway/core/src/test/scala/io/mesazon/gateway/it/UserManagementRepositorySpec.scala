package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

import PostgreSQLTestClient.PostgreSQLTestClientConfig

class UserManagementRepositorySpec extends ZWordSpecBase, GatewayArbitraries, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userOnboardTable = "user_onboard",
    userDetailsTable = "user_details",
    userOtpTable = "user_otp",
    userRefreshTokenTable = "user_refresh_token",
  )

  def withContext[A](f: (PostgreSQLTestClient, UserManagementQueries) => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue
    val userManagementQueries =
      ZIO
        .service[UserManagementQueries]
        .provide(UserManagementQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    f(postgreSQLTestClient, userManagementQueries)
  }

  override def beforeAll(): Unit = withContext { (postgresClient, _) =>
    super.beforeAll()
    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userDetailsTable)
        .zioValue shouldBe true
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userOtpTable)
        .zioValue shouldBe true
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userOnboardTable)
        .zioValue shouldBe true
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userRefreshTokenTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { (postgresClient, _) =>
    super.beforeEach()
    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userOtpTable).zioValue
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userOnboardTable).zioValue
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userDetailsTable).zioValue
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userRefreshTokenTable).zioValue
    }
  }

  "UserManagementRepository" when {
    "insertUserOnboardEmail" should {
      "successfully insert onboard user email" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val email                    = arbitrarySample[Email]
        val onboardStage             = arbitrarySample[OnboardStage]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val userOnboardRow = userManagementRepository.insertUserOnboardEmail(email, onboardStage).zioValue

        postgresClient
          .executeQuery(
            userManagementQueries.getUserOnboard(userOnboardRow.userID)
          )
          .zioValue shouldBe Some(
          UserOnboardRow(
            userID = userOnboardRow.userID,
            email = email,
            fullName = None,
            passwordHash = None,
            phoneNumber = None,
            stage = onboardStage,
            createdAt = CreatedAt(now),
            updatedAt = UpdatedAt(now),
          )
        )
      }

      "not fail when email already exist" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val email                    = arbitrarySample[Email]
        val onboardStage             = arbitrarySample[OnboardStage]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorConstLive("fixed-id"),
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val userOnboardRow1 = userManagementRepository.insertUserOnboardEmail(email, onboardStage).zioValue
        val userOnboardRow2 = userManagementRepository.insertUserOnboardEmail(email, onboardStage).zioValue

        postgresClient
          .executeQuery(
            userManagementQueries.getUserOnboard(userOnboardRow1.userID)
          )
          .zioValue shouldBe Some(
          UserOnboardRow(
            userID = userOnboardRow1.userID,
            email = email,
            fullName = None,
            passwordHash = None,
            phoneNumber = None,
            stage = onboardStage,
            createdAt = CreatedAt(now),
            updatedAt = UpdatedAt(now),
          )
        )

        userOnboardRow1 shouldBe userOnboardRow2
      }

      "fail when user id already exist" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val email1                   = arbitrarySample[Email]
        val email2                   = arbitrarySample[Email]
        val onboardStage             = arbitrarySample[OnboardStage]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorConstLive("fixed-id"),
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val userOnboardRow1 = userManagementRepository.insertUserOnboardEmail(email1, onboardStage).zioValue
        val error           = userManagementRepository.insertUserOnboardEmail(email2, onboardStage).zioError

        postgresClient
          .executeQuery(
            userManagementQueries.getUserOnboard(userOnboardRow1.userID)
          )
          .zioValue shouldBe Some(
          UserOnboardRow(
            userID = userOnboardRow1.userID,
            email = email1,
            fullName = None,
            passwordHash = None,
            phoneNumber = None,
            stage = onboardStage,
            createdAt = CreatedAt(now),
            updatedAt = UpdatedAt(now),
          )
        )

        error.message shouldBe s"Failed to insertUserOnboardEmail: [${email2.value}], [$onboardStage]"
        error.underlying.value shouldBe a[DbException]
      }
    }

    "updateUserOnboard" should {
      "successfully update onboard user details" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val updateFullName           = arbitrarySample[Option[FullName]]
        val updatePhoneNumber        = arbitrarySample[Option[PhoneNumberE164]]
        val updatePasswordHash       = arbitrarySample[Option[PasswordHash]]
        val updateOnboardStage       = arbitrarySample[OnboardStage]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        postgresClient.executeQuery(userManagementQueries.insertUserOnboardEmail(userOnboardRow)).zioValue

        userManagementRepository
          .updateUserOnboard(
            userID = userOnboardRow.userID,
            stage = updateOnboardStage,
            fullName = updateFullName,
            phoneNumber = updatePhoneNumber,
            passwordHash = updatePasswordHash,
          )
          .zioValue

        postgresClient
          .executeQuery(
            userManagementQueries.getUserOnboard(userOnboardRow.userID)
          )
          .zioValue shouldBe Some(
          userOnboardRow.copy(
            fullName = updateFullName orElse userOnboardRow.fullName,
            phoneNumber = updatePhoneNumber orElse userOnboardRow.phoneNumber,
            passwordHash = updatePasswordHash orElse userOnboardRow.passwordHash,
            stage = updateOnboardStage,
            updatedAt = UpdatedAt(now),
          )
        )
      }

      "should fail to update onboard user details when user is not found, entry should remain empty" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val notExistingUserID        = arbitrarySample[UserID]
          val updateOnboardStage       = arbitrarySample[OnboardStage]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val error = userManagementRepository
            .updateUserOnboard(
              userID = notExistingUserID,
              fullName = None,
              phoneNumber = None,
              passwordHash = None,
              stage = updateOnboardStage,
            )
            .zioError

          postgresClient
            .executeQuery(
              userManagementQueries.getUserOnboard(notExistingUserID)
            )
            .zioValue shouldBe None

          error.message shouldBe s"Failed to updateUserOnboard: [${notExistingUserID.value}], [${updateOnboardStage.toString}], [None], [None]"
          error.underlying.value shouldBe a[DbException]
      }
    }

    "getUserOnboard" should {
      "successfully get user onboard" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertedUserOnboardRow =
          postgresClient.executeQuery(userManagementQueries.insertUserOnboardEmail(userOnboardRow)).zioValue

        val maybeUserOnboardRow = userManagementRepository.getUserOnboard(userOnboardRow.userID).zioValue

        maybeUserOnboardRow.value shouldBe insertedUserOnboardRow
      }

      "return None when user onboard details is not found" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userID                   = arbitrarySample[UserID]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val maybeUserOnboardRow = userManagementRepository.getUserOnboard(userID).zioValue

        maybeUserOnboardRow shouldBe None
      }
    }

    "getUserOnboardByEmail" should {
      "successfully get user onboard by email" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertedUserOnboard =
          postgresClient.executeQuery(userManagementQueries.insertUserOnboardEmail(userOnboardRow)).zioValue

        val maybeUserOnboard = userManagementRepository.getUserOnboardByEmail(userOnboardRow.email).zioValue

        maybeUserOnboard.value shouldBe insertedUserOnboard
      }

      "return None when user onboard details is not found by email" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val email                    = arbitrarySample[Email]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val maybeUserOnboard = userManagementRepository.getUserOnboardByEmail(email).zioValue

          maybeUserOnboard shouldBe None
      }
    }

    "upsertUserOtp" should {
      "successfully insert user otp" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOtpRow               = arbitrarySample[UserOtpRow]
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertUserOnboardRow =
          userManagementRepository.insertUserOnboardEmail(userOnboardRow.email, userOnboardRow.stage).zioValue

        val insertUserOtpRow = userManagementRepository
          .upsertUserOtp(
            userID = insertUserOnboardRow.userID,
            otp = userOtpRow.otp,
            otpType = userOtpRow.otpType,
            expiresAt = userOtpRow.expiresAt,
          )
          .zioValue

        val getUserOtpRow =
          postgresClient.database.transactionOrDie(userManagementQueries.getUserOtp(OtpID.assume("2"))).zioValue

        val expectedUserOtpRow = UserOtpRow(
          otpID = OtpID.assume("2"),
          userID = insertUserOnboardRow.userID,
          otp = userOtpRow.otp,
          otpType = userOtpRow.otpType,
          createdAt = CreatedAt(now),
          updatedAt = UpdatedAt(now),
          expiresAt = userOtpRow.expiresAt,
        )

        getUserOtpRow.value shouldBe expectedUserOtpRow
        getUserOtpRow.value shouldBe insertUserOtpRow
      }

      "successfully upsert user otp if otp already exist for the same user and otp type" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val userOtpRow               = arbitrarySample[UserOtpRow]
          val userOnboardRow           = arbitrarySample[UserOnboardRow]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val userManagementRepositoryUpsert = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)),
              Mocks.idGeneratorConstLive("upsert-id"),
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val insertUserOnboardRow =
            userManagementRepository.insertUserOnboardEmail(userOnboardRow.email, userOnboardRow.stage).zioValue

          val insertUserOtpRow = userManagementRepository
            .upsertUserOtp(
              userID = insertUserOnboardRow.userID,
              otp = userOtpRow.otp,
              otpType = userOtpRow.otpType,
              expiresAt = userOtpRow.expiresAt,
            )
            .zioValue

          insertUserOtpRow shouldBe UserOtpRow(
            otpID = OtpID.assume("2"),
            userID = insertUserOnboardRow.userID,
            otp = userOtpRow.otp,
            otpType = userOtpRow.otpType,
            createdAt = CreatedAt(now),
            updatedAt = UpdatedAt(now),
            expiresAt = userOtpRow.expiresAt,
          )

          val otpUpsert       = arbitrarySample[Otp]
          val expiresAtUpsert = ExpiresAt.assume(userOtpRow.expiresAt.value.minusSeconds(10))

          val upsertUserOtp = userManagementRepositoryUpsert
            .upsertUserOtp(
              userID = insertUserOnboardRow.userID,
              otp = otpUpsert,
              otpType = userOtpRow.otpType,
              expiresAt = expiresAtUpsert,
            )
            .zioValue

          val userOtpRowUpsert =
            postgresClient.database.transactionOrDie(userManagementQueries.getUserOtp(upsertUserOtp.otpID)).zioValue

          userOtpRowUpsert.value shouldBe UserOtpRow(
            otpID = OtpID.assume("upsert-id"),
            userID = insertUserOnboardRow.userID,
            otp = otpUpsert,
            otpType = userOtpRow.otpType,
            createdAt = CreatedAt(now),
            updatedAt = UpdatedAt(now.plusSeconds(5)),
            expiresAt = expiresAtUpsert,
          )
      }
    }

    "updateUserOtp" should {
      "successfully update user otp" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOtpRow               = arbitrarySample[UserOtpRow]
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val userManagementRepositoryUpdate = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertUserOnboardRow =
          userManagementRepository.insertUserOnboardEmail(userOnboardRow.email, userOnboardRow.stage).zioValue

        val insertUserOtpRow = userManagementRepository
          .upsertUserOtp(
            userID = insertUserOnboardRow.userID,
            otp = userOtpRow.otp,
            otpType = userOtpRow.otpType,
            expiresAt = userOtpRow.expiresAt,
          )
          .zioValue

        val updateUserOtpRow = userManagementRepositoryUpdate
          .updateUserOtp(insertUserOtpRow.otpID, ExpiresAt.assume(userOtpRow.expiresAt.value.plusSeconds(7)))
          .zioValue

        val expectedUserOtpRow = UserOtpRow(
          otpID = insertUserOtpRow.otpID,
          userID = insertUserOnboardRow.userID,
          otp = userOtpRow.otp,
          otpType = userOtpRow.otpType,
          createdAt = CreatedAt(now),
          updatedAt = UpdatedAt(now.plusSeconds(5)),
          expiresAt = ExpiresAt.assume(userOtpRow.expiresAt.value.plusSeconds(7)),
        )

        val userOtpRowResult =
          postgresClient.database.transactionOrDie(userManagementQueries.getUserOtp(insertUserOtpRow.otpID)).zioValue

        userOtpRowResult.value shouldBe expectedUserOtpRow
        userOtpRowResult.value shouldBe updateUserOtpRow
      }
    }

    "deleteUserOtp" should {
      "successfully delete user otp by otp id" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOtpRow               = arbitrarySample[UserOtpRow]
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertUserOnboardRow =
          userManagementRepository.insertUserOnboardEmail(userOnboardRow.email, userOnboardRow.stage).zioValue

        val insertUserOtpRow = userManagementRepository
          .upsertUserOtp(insertUserOnboardRow.userID, userOtpRow.otp, userOtpRow.otpType, userOtpRow.expiresAt)
          .zioValue

        userManagementRepository.deleteUserOtp(insertUserOtpRow.otpID).zioValue

        val userOtpRows =
          postgresClient.database.transactionOrDie(userManagementQueries.getAllUserOtpRows).zioValue

        userOtpRows shouldBe empty
      }

      "successfully delete user otp that doesn't exist" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOtpRow               = arbitrarySample[UserOtpRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val deleteUserOtpResult = userManagementRepository.deleteUserOtp(userOtpRow.otpID).zioEither

        assert(deleteUserOtpResult.isRight)
      }
    }

    "getUserOtp" should {
      "successfully get user otp by otp id" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOtpRow               = arbitrarySample[UserOtpRow]
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertUserOnboardRow =
          userManagementRepository.insertUserOnboardEmail(userOnboardRow.email, userOnboardRow.stage).zioValue

        val insertUserOtpRow = userManagementRepository
          .upsertUserOtp(
            userID = insertUserOnboardRow.userID,
            otp = userOtpRow.otp,
            otpType = userOtpRow.otpType,
            expiresAt = userOtpRow.expiresAt,
          )
          .zioValue

        val maybeUserOtpRow =
          userManagementRepository.getUserOtp(insertUserOtpRow.otpID).zioValue

        maybeUserOtpRow.value shouldBe insertUserOtpRow
      }

      "return None when user otp is not found by otp id" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val otpID                    = OtpID.assume("non-existing-otp-id")
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val maybeUserOtpRow = userManagementRepository.getUserOtp(otpID).zioValue

        maybeUserOtpRow shouldBe None
      }
    }

    "getUserOtpByUserID" should {
      "successfully get user otp by user id and otp type" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOtpRow               = arbitrarySample[UserOtpRow]
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertUserOnboardRow =
          userManagementRepository.insertUserOnboardEmail(userOnboardRow.email, userOnboardRow.stage).zioValue

        val insertUserOtpRow = userManagementRepository
          .upsertUserOtp(
            userID = insertUserOnboardRow.userID,
            otp = userOtpRow.otp,
            otpType = userOtpRow.otpType,
            expiresAt = userOtpRow.expiresAt,
          )
          .zioValue

        val maybeUserOtpRow =
          userManagementRepository.getUserOtpByUserID(insertUserOnboardRow.userID, userOtpRow.otpType).zioValue

        maybeUserOtpRow.value shouldBe insertUserOtpRow
      }

      "return None when user otp is not found by user id and otp type" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val userID                   = arbitrarySample[UserID]
          val otpType                  = arbitrarySample[OtpType]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val maybeUserOtpRow =
            userManagementRepository.getUserOtpByUserID(userID, otpType).zioValue

          maybeUserOtpRow shouldBe None
      }
    }

    "upsertUserRefreshToken" should {
      "successfully upsert refresh token" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertedUserOnboardRow = postgresClient.database
          .transactionOrDie(userManagementQueries.insertUserOnboardEmail(userOnboardRow))
          .zioValue

        val userRefreshTokenRow = arbitrarySample[UserRefreshTokenRow].copy(userID = insertedUserOnboardRow.userID)

        userManagementRepository
          .upsertUserRefreshToken(
            userID = userRefreshTokenRow.userID,
            tokenID = userRefreshTokenRow.tokenID,
            expiresAt = userRefreshTokenRow.expiresAt,
          )
          .zioValue

        val getRefreshTokenRow =
          postgresClient.database
            .transactionOrDie(
              userManagementQueries.getUserRefreshToken(userRefreshTokenRow.tokenID, userRefreshTokenRow.userID)
            )
            .zioValue

        getRefreshTokenRow.value shouldBe userRefreshTokenRow.copy(
          createdAt = CreatedAt(now)
        )
      }

      "successfully upsert refresh token if token already exist for the same user" in withContext {
        (postgresClient, userManagementQueries) =>
          val now        = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow   = Clock.fixed(now, ZoneOffset.UTC)
          val oldTokenID = arbitrarySample[TokenID]

          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val userOnboardRow = arbitrarySample[UserOnboardRow]

          postgresClient.database
            .transactionOrDie(userManagementQueries.insertUserOnboardEmail(userOnboardRow))
            .zioValue

          val userRefreshTokenRow1 = arbitrarySample[UserRefreshTokenRow]
            .copy(
              userID = userOnboardRow.userID,
              tokenID = oldTokenID,
              createdAt = CreatedAt(now.minusSeconds(10)),
              expiresAt = ExpiresAt.assume(now.plusSeconds(3600)),
            )

          val userRefreshTokenRow2 = arbitrarySample[UserRefreshTokenRow]
            .copy(userID = userOnboardRow.userID, createdAt = CreatedAt(now))

          postgresClient.database
            .transactionOrDie(
              userManagementQueries
                .upsertUserRefreshToken(userRefreshTokenRow1, None)
            )
            .zioValue

          userManagementRepository
            .upsertUserRefreshToken(
              userID = userRefreshTokenRow2.userID,
              tokenID = userRefreshTokenRow2.tokenID,
              expiresAt = userRefreshTokenRow2.expiresAt,
              maybeOldTokenID = Some(oldTokenID),
            )
            .zioValue

          val userRefreshTokenRows =
            postgresClient.database
              .transactionOrDie(userManagementQueries.getAllUserRefreshTokens(userOnboardRow.userID))
              .zioValue

          userRefreshTokenRows should have size 1
          userRefreshTokenRows should contain theSameElementsAs List(userRefreshTokenRow2)
      }
    }

    "getUserRefreshToken" should {
      "successfully get user refresh token by token id" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertedUserOnboardRow = postgresClient.database
          .transactionOrDie(userManagementQueries.insertUserOnboardEmail(userOnboardRow))
          .zioValue

        val userRefreshTokenRow = arbitrarySample[UserRefreshTokenRow].copy(userID = insertedUserOnboardRow.userID)

        postgresClient.database
          .transactionOrDie(userManagementQueries.upsertUserRefreshToken(userRefreshTokenRow, None))
          .zioValue

        val maybeUserRefreshTokenRow =
          userManagementRepository
            .getUserRefreshToken(userRefreshTokenRow.tokenID, insertedUserOnboardRow.userID)
            .zioValue

        maybeUserRefreshTokenRow.value shouldBe userRefreshTokenRow
      }

      "return None when user refresh token is not found by token id" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val tokenID                  = arbitrarySample[TokenID]
          val userID                   = arbitrarySample[UserID]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val maybeUserRefreshTokenRow =
            userManagementRepository.getUserRefreshToken(tokenID, userID).zioValue

          maybeUserRefreshTokenRow shouldBe None
      }
    }

    "deleteUserRefreshToken" should {
      "successfully delete user refresh token by token id" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        val insertedUserOnboardRow = postgresClient.database
          .transactionOrDie(userManagementQueries.insertUserOnboardEmail(userOnboardRow))
          .zioValue

        val userRefreshTokenRow = arbitrarySample[UserRefreshTokenRow].copy(userID = insertedUserOnboardRow.userID)

        postgresClient.database
          .transactionOrDie(userManagementQueries.upsertUserRefreshToken(userRefreshTokenRow, None))
          .zioValue

        userManagementRepository
          .deleteUserRefreshToken(userRefreshTokenRow.tokenID, userRefreshTokenRow.userID)
          .zioValue

        val maybeUserRefreshTokenRow = postgresClient.database
          .transactionOrDie(userManagementQueries.getAllUserRefreshTokens(userRefreshTokenRow.userID))
          .zioValue

        maybeUserRefreshTokenRow shouldBe empty
      }

      "successfully delete user refresh token that doesn't exist" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val tokenID                  = arbitrarySample[TokenID]
          val userID                   = arbitrarySample[UserID]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val deleteUserRefreshTokenResult =
            userManagementRepository.deleteUserRefreshToken(tokenID, userID).zioEither

          assert(deleteUserRefreshTokenResult.isRight)
      }
    }

    "deleteAllUserRefreshTokens" should {
      "successfully delete all user refresh tokens by user id" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val userOnboardRow           = arbitrarySample[UserOnboardRow]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val insertedUserOnboardRow = postgresClient.database
            .transactionOrDie(userManagementQueries.insertUserOnboardEmail(userOnboardRow))
            .zioValue

          val userRefreshTokenRow1 = arbitrarySample[UserRefreshTokenRow].copy(userID = insertedUserOnboardRow.userID)
          val userRefreshTokenRow2 = arbitrarySample[UserRefreshTokenRow].copy(userID = insertedUserOnboardRow.userID)

          postgresClient.database
            .transactionOrDie(userManagementQueries.upsertUserRefreshToken(userRefreshTokenRow1, None))
            .zioValue

          postgresClient.database
            .transactionOrDie(userManagementQueries.upsertUserRefreshToken(userRefreshTokenRow2, None))
            .zioValue

          userManagementRepository.deleteAllUserRefreshTokens(insertedUserOnboardRow.userID).zioValue

          val maybeUserRefreshTokenRows = postgresClient.database
            .transactionOrDie(userManagementQueries.getAllUserRefreshTokens(insertedUserOnboardRow.userID))
            .zioValue

          maybeUserRefreshTokenRows shouldBe empty
      }
    }

    "insertUserDetails" should {
      "successfully insert user details" in withContext { (postgresClient, userManagementQueries) =>
        val now                                                = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                                           = Clock.fixed(now, ZoneOffset.UTC)
        val onboardUserDetails                                 = arbitrarySample[OnboardUserDetails]
        val userID                                             = arbitrarySample[UserID]
        val email                                              = arbitrarySample[Email]
        val userManagementRepository: UserManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        userManagementRepository.insertUserDetails(userID, email, onboardUserDetails).zioValue

        postgresClient
          .executeQuery(userManagementQueries.getUserDetailsQuery(userID))
          .zioValue shouldBe Some(
          onboardUserDetails
            .into[UserDetailsRow]
            .withFieldConst(_.userID, userID)
            .withFieldConst(_.email, email)
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform
        )
      }

      "fail with UserAlreadyExists when user already exist" in withContext { (postgresClient, userManagementQueries) =>
        val onboardUserDetails       = arbitrarySample[OnboardUserDetails]
        val userID                   = arbitrarySample[UserID]
        val email                    = arbitrarySample[Email]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            TimeProvider.liveSystemUTC,
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        userManagementRepository.insertUserDetails(userID, email, onboardUserDetails).zioValue

        userManagementRepository
          .insertUserDetails(userID, email, onboardUserDetails)
          .zioError shouldBe ServiceError.ConflictError
          .UserAlreadyExists(
            userID,
            email,
          )
      }
    }

    "updateUserDetails" should {
      "successfully update user details" in withContext { (postgresClient, userManagementQueries) =>
        val usersDetailsRow          = arbitrarySample[UserDetailsRow]
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val updateUserDetails        = arbitrarySample[UpdateUserDetails]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(clockNow),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        postgresClient.database.transactionOrDie(userManagementQueries.insertUserDetailsQuery(usersDetailsRow)).zioValue

        userManagementRepository.updateUserDetails(usersDetailsRow.userID, updateUserDetails).zioValue

        val updatedUserDetailsRow = usersDetailsRow.copy(
          firstName = updateUserDetails.firstName.getOrElse(usersDetailsRow.firstName),
          lastName = updateUserDetails.lastName.getOrElse(usersDetailsRow.lastName),
          phoneNumber = updateUserDetails.phoneNumber.getOrElse(usersDetailsRow.phoneNumber),
          addressLine1 = updateUserDetails.addressLine1.getOrElse(usersDetailsRow.addressLine1),
          addressLine2 = updateUserDetails.addressLine2.orElse(usersDetailsRow.addressLine2),
          city = updateUserDetails.city.getOrElse(usersDetailsRow.city),
          postalCode = updateUserDetails.postalCode.getOrElse(usersDetailsRow.postalCode),
          company = updateUserDetails.company.getOrElse(usersDetailsRow.company),
          updatedAt = UpdatedAt(now),
        )

        postgresClient.database
          .transactionOrDie(userManagementQueries.getUserDetailsQuery(usersDetailsRow.userID))
          .zioValue shouldBe Some(updatedUserDetailsRow)
      }

      "successfully update occurs on user that is not found, entry should remain empty" in withContext {
        (postgresClient, userManagementQueries) =>
          val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
          val updateUserDetails        = arbitrarySample[UpdateUserDetails]
          val userID                   = arbitrarySample[UserID]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          userManagementRepository.updateUserDetails(userID, updateUserDetails).zioValue

          postgresClient.executeQuery(userManagementQueries.getUserDetailsQuery(userID)).zioValue shouldBe None
      }
    }
  }
}
