package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.mock.timeProviderMockLive
import io.mesazon.gateway.repository.UserRepository
import io.mesazon.gateway.repository.domain.UserDetailsRow
import io.mesazon.gateway.repository.queries.UserDetailsQueries
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

import PostgreSQLTestClient.PostgreSQLTestClientConfig

class UserRepositorySpec extends ZWordSpecBase, GatewayArbitraries, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val schema           = "local_schema"
  val userDetailsTable = "users_details"

  def withContext[A](f: PostgreSQLTestClient => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
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
        val userRepository     = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        userRepository.insertUserDetails(userID, email, onboardUserDetails).zioValue

        client.database
          .transactionOrDie(UserDetailsQueries.getUserDetailsQuery(userID))
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

      "fail with UserAlreadyExists when user already exist" in withContext { (client: PostgreSQLTestClient) =>
        val onboardUserDetails = arbitrarySample[OnboardUserDetails]
        val userID             = arbitrarySample[UserID]
        val email              = arbitrarySample[Email]
        val userRepository     = ZIO
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
        val usersDetailsRow   = arbitrarySample[UserDetailsRow]
        val now               = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow          = Clock.fixed(now, ZoneOffset.UTC)
        val updateUserDetails = arbitrarySample[UpdateUserDetails]
        val userRepository    = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        client.database.transactionOrDie(UserDetailsQueries.insertUserDetailsQuery(usersDetailsRow)).zioValue

        userRepository.updateUserDetails(usersDetailsRow.userID, updateUserDetails).zioValue

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

        client.database
          .transactionOrDie(UserDetailsQueries.getUserDetailsQuery(usersDetailsRow.userID))
          .zioValue shouldBe Some(updatedUserDetailsRow)
      }

      "successfully update occurs on user that is not found, entry should remain empty" in withContext {
        (client: PostgreSQLTestClient) =>
          val now               = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow          = Clock.fixed(now, ZoneOffset.UTC)
          val updateUserDetails = arbitrarySample[UpdateUserDetails]
          val userID            = arbitrarySample[UserID]
          val userRepository    = ZIO
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
