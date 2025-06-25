package io.rikkos.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.*
import io.rikkos.gateway.mock.timeProviderMockLive
import io.rikkos.gateway.query.*
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class UserRepositorySpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val schema           = "local_schema"
  val userDetailsTable = "users_details"

  def withContext[A](f: PostgreSQLTestClient => A): A = withContainers { container =>
    val config = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue

    f(postgreSQLTestClient)
  }

  override def beforeAll(): Unit = withContext { client =>
    super.beforeAll()
    eventually {
      client.checkIfTableExists(schema, userDetailsTable).zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { client =>
    super.beforeEach()
    eventually {
      client.truncateTable(schema, userDetailsTable).zioValue
    }
  }

  "UserRepository" when {
    "insertUserDetails" should {
      "successfully insert user details" in withContext { (client: PostgreSQLTestClient) =>
        val now                = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow           = Clock.fixed(now, ZoneOffset.UTC)
        val onboardUserDetails = arbitrarySample[OnboardUserDetails]
        val userID             = arbitrarySample[UserID]
        val email              = arbitrarySample[Email]
        val userRepository = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        userRepository.insertUserDetails(userID, email, onboardUserDetails).zioValue

        client.database
          .transactionOrDie(UserDetailsQueries.getUserDetailsQuery(userID))
          .zioValue shouldBe Some(
          onboardUserDetails
            .into[UserDetailsTable]
            .withFieldConst(_.userID, userID)
            .withFieldConst(_.email, email)
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform
        )
      }

      "fail with UserAlreadyExists when user already exist" in withContext { (client: PostgreSQLTestClient) =>
        val onboardUserDetails = arbitrarySample[OnboardUserDetails]
        val userID             = arbitrarySample[UserID]
        val email              = arbitrarySample[Email]
        val userRepository = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), TimeProvider.liveSystemUTC)
          .zioValue

        userRepository.insertUserDetails(userID, email, onboardUserDetails).zioValue

        // Attempt to insert the same user again
        userRepository.insertUserDetails(userID, email, onboardUserDetails).zioError shouldBe ServiceError.ConflictError
          .UserAlreadyExists(
            userID,
            email,
          )
      }
    }

    "updateUserDetails" should {
      "successfully update user details" in withContext { (client: PostgreSQLTestClient) =>
        val usersDetailsTable = arbitrarySample[UserDetailsTable]
        val now               = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow          = Clock.fixed(now, ZoneOffset.UTC)
        val updateUserDetails = arbitrarySample[UpdateUserDetails]
        val userRepository = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        client.database.transactionOrDie(UserDetailsQueries.insertUserDetailsQuery(usersDetailsTable)).zioValue

        userRepository.updateUserDetails(usersDetailsTable.userID, updateUserDetails).zioValue

        val updatedUserDetailsTable = usersDetailsTable.copy(
          firstName = updateUserDetails.firstName.getOrElse(usersDetailsTable.firstName),
          lastName = updateUserDetails.lastName.getOrElse(usersDetailsTable.lastName),
          countryCode = updateUserDetails.countryCode.getOrElse(usersDetailsTable.countryCode),
          phoneNumber = updateUserDetails.phoneNumber.getOrElse(usersDetailsTable.phoneNumber),
          addressLine1 = updateUserDetails.addressLine1.getOrElse(usersDetailsTable.addressLine1),
          addressLine2 = updateUserDetails.addressLine2.orElse(usersDetailsTable.addressLine2),
          city = updateUserDetails.city.getOrElse(usersDetailsTable.city),
          postalCode = updateUserDetails.postalCode.getOrElse(usersDetailsTable.postalCode),
          company = updateUserDetails.company.getOrElse(usersDetailsTable.company),
        )

        client.database
          .transactionOrDie(UserDetailsQueries.getUserDetailsQuery(usersDetailsTable.userID))
          .zioValue shouldBe Some(updatedUserDetailsTable)
      }

      "fail with UserNotExist when user doesn't exist" in withContext { (client: PostgreSQLTestClient) =>
        val now               = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow          = Clock.fixed(now, ZoneOffset.UTC)
        val updateUserDetails = arbitrarySample[UpdateUserDetails]
        val userID            = arbitrarySample[UserID]
        val userRepository = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        userRepository.updateUserDetails(userID, updateUserDetails).zioValue

        client.database
          .transactionOrDie(UserDetailsQueries.getUserDetailsQuery(userID))
          .zioValue shouldBe None
      }
    }
  }
}
