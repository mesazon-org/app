package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.repository.domain.{UserDetailsRow, UserOnboardRow}
import io.mesazon.gateway.repository.queries.UserManagementQueries
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

import PostgreSQLTestClient.PostgreSQLTestClientConfig

class UserManagementRepositorySpec extends ZWordSpecBase, GatewayArbitraries, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userOnboardTable = "user_onboard",
    userDetailsTable = "user_details",
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
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userOnboardTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { (postgresClient, _) =>
    super.beforeEach()
    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userDetailsTable).zioValue
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userOnboardTable).zioValue
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
            userManagementQueries.getOnboardUser(userOnboardRow.userID)
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

      "fail with UserAlreadyExists when user id already exist" in withContext {
        (postgresClient, userManagementQueries) =>
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

          val userOnboardRow = userManagementRepository.insertUserOnboardEmail(email1, onboardStage).zioValue
          val error = userManagementRepository.insertUserOnboardEmail(email2, onboardStage).zioEither.left.value

          postgresClient
            .executeQuery(
              userManagementQueries.getOnboardUser(userOnboardRow.userID)
            )
            .zioValue shouldBe Some(
            UserOnboardRow(
              userID = userOnboardRow.userID,
              email = email1,
              fullName = None,
              passwordHash = None,
              phoneNumber = None,
              stage = onboardStage,
              createdAt = CreatedAt(now),
              updatedAt = UpdatedAt(now),
            )
          )

          error shouldBe ServiceError.ConflictError.UserAlreadyExists(
            userID = UserID.assume("fixed-id"),
            email = email2,
          )
      }

      "fail with UserAlreadyExists when email already exist" in withContext { (postgresClient, userManagementQueries) =>
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
        val error          = userManagementRepository.insertUserOnboardEmail(email, onboardStage).zioEither.left.value

        postgresClient
          .executeQuery(
            userManagementQueries.getOnboardUser(userOnboardRow.userID)
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

        error shouldBe ServiceError.ConflictError.UserAlreadyExists(
          userID = UserID.assume("2"),
          email = email,
        )
      }
    }

    "updateUserOnboard" should {
      "successfully update onboard user details" in withContext { (postgresClient, userManagementQueries) =>
        val now                      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow                 = Clock.fixed(now, ZoneOffset.UTC)
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val updateFullName           = arbitrarySample[FullName]
        val updatePhoneNumber        = arbitrarySample[PhoneNumberE164]
        val updatePasswordHash       = arbitrarySample[PasswordHash]
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

        postgresClient.executeQuery(userManagementQueries.insertUserOnboard(userOnboardRow)).zioValue

        userManagementRepository
          .updateUserOnboard(
            userID = userOnboardRow.userID,
            fullName = Some(updateFullName),
            phoneNumber = Some(updatePhoneNumber),
            passwordHash = Some(updatePasswordHash),
            stage = updateOnboardStage,
          )
          .zioValue

        postgresClient
          .executeQuery(
            userManagementQueries.getOnboardUser(userOnboardRow.userID)
          )
          .zioValue shouldBe Some(
          userOnboardRow.copy(
            fullName = Some(updateFullName),
            phoneNumber = Some(updatePhoneNumber),
            passwordHash = Some(updatePasswordHash),
            stage = updateOnboardStage,
            updatedAt = UpdatedAt(now),
          )
        )
      }

      "not failed to update onboard user details when user is not found, entry should remain empty" in withContext {
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

          userManagementRepository
            .updateUserOnboard(
              userID = notExistingUserID,
              fullName = None,
              phoneNumber = None,
              passwordHash = None,
              stage = updateOnboardStage,
            )
            .zioValue

          postgresClient
            .executeQuery(
              userManagementQueries.getOnboardUser(notExistingUserID)
            )
            .zioValue shouldBe None
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

        // Attempt to insert the same user again
        userManagementRepository
          .insertUserDetails(userID, email, onboardUserDetails)
          .zioError shouldBe ServiceError.ConflictError
          .UserAlreadyExists(
            userID,
            email,
          )
      }
    }

    "getUserOnboard" should {
      "successfully get user onboard" in withContext { (postgresClient, userManagementQueries) =>
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(userOnboardRow.createdAt.value, ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        postgresClient.executeQuery(userManagementQueries.insertUserOnboard(userOnboardRow)).zioValue

        val result = userManagementRepository.getUserOnboard(userOnboardRow.userID).zioValue

        result shouldBe userOnboardRow
      }

      "fail with UserNotFoundError when user onboard details is not found" in withContext {
        (postgresClient, userManagementQueries) =>
          val userID                   = arbitrarySample[UserID]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val error = userManagementRepository.getUserOnboard(userID).zioEither.left.value

          error shouldBe ServiceError.InternalServerError.UserNotFoundError(
            s"UserOnboard with userId [$userID] couldn't be found"
          )
      }
    }

    "getUserOnboardByEmail" should {
      "successfully get user onboard by email" in withContext { (postgresClient, userManagementQueries) =>
        val userOnboardRow           = arbitrarySample[UserOnboardRow]
        val userManagementRepository = ZIO
          .service[UserManagementRepository]
          .provide(
            UserManagementRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(userOnboardRow.createdAt.value, ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userManagementQueries),
          )
          .zioValue

        postgresClient.executeQuery(userManagementQueries.insertUserOnboard(userOnboardRow)).zioValue

        val result = userManagementRepository.getUserOnboardByEmail(userOnboardRow.email).zioValue

        result shouldBe userOnboardRow
      }

      "fail with UserNotFoundError when user onboard details is not found by email" in withContext {
        (postgresClient, userManagementQueries) =>
          val email                    = arbitrarySample[Email]
          val userManagementRepository = ZIO
            .service[UserManagementRepository]
            .provide(
              UserManagementRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userManagementQueries),
            )
            .zioValue

          val error = userManagementRepository.getUserOnboardByEmail(email).zioEither.left.value

          error shouldBe ServiceError.InternalServerError.UserNotFoundError(
            s"UserOnboard with email [$email] couldn't be found"
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
