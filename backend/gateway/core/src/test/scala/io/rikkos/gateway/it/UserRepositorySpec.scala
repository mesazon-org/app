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

  override def dockerComposeFile: String            = "./src/test/resources/compose.yaml"
  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val schema           = "local_schema"
  val userDetailsTable = "user_details"

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

  "UserRepository" when {
    "insertUserDetails" should {
      "successfully insert user details" in withContext { (client: PostgreSQLTestClient) =>
        val now         = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow    = Clock.fixed(now, ZoneOffset.UTC)
        val userDetails = arbitrarySample[UserDetails]
        val userRepository = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        userRepository.insertUserDetails(userDetails).zioValue

        client.database
          .transactionOrDie(UserDetailsQueries.getUserDetailsQuery(userDetails.userID))
          .zioValue shouldBe Some(
          userDetails
            .into[UserDetailsTable]
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform
        )
      }

      "fail with UserAlreadyExists when user already exist" in withContext { (client: PostgreSQLTestClient) =>
        val userDetails = arbitrarySample[UserDetails]
        val userRepository = ZIO
          .service[UserRepository]
          .provide(UserRepository.live, ZLayer.succeed(client.database), TimeProvider.liveSystemUTC)
          .zioValue

        userRepository.insertUserDetails(userDetails).zioValue

        // Attempt to insert the same user again
        userRepository.insertUserDetails(userDetails).zioError shouldBe ServiceError.ConflictError.UserAlreadyExists(
          userDetails.userID,
          userDetails.email,
        )
      }
    }
  }
}
