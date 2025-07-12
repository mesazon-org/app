package io.rikkos.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.rikkos.domain.*
import io.rikkos.gateway.mock.timeProviderMockLive
import io.rikkos.gateway.query.{UserContactsQueries, UserDetailsQueries}
import io.rikkos.gateway.repository.UserContactsRepository
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.{DockerComposeBase, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class UserContactsRepositorySpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {
  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val schema             = "local_schema"
  val usersContactsTable = "users_contacts"

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
      client.checkIfTableExists(schema, usersContactsTable).zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { client =>
    super.beforeEach()
    eventually {
      client.truncateTable(schema, usersContactsTable).zioValue
    }
  }

  "UserContactsRepository" when {
    "upsertUserContacts" should {
      "successfully insert user contacts" in withContext { (client: PostgreSQLTestClient) =>
        val now      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow = Clock.fixed(now, ZoneOffset.UTC)
        val userID   = arbitrarySample[UserID]
        val userDetailsTable = arbitrarySample[UserDetailsTable]
          .copy(userID = userID)
        val upsertUserContacts = arbitrarySample[UpsertUserContact](5)
        val userContactsRepository = ZIO
          .service[UserContactsRepository]
          .provide(UserContactsRepository.live, ZLayer.succeed(client.database), timeProviderMockLive(clockNow))
          .zioValue

        // Insert user for user_id foreign key constraint
        client.database.transactionOrDie(UserDetailsQueries.insertUserDetailsQuery(userDetailsTable)).zioValue

        userContactsRepository.upsertUserContacts(userID, upsertUserContacts).zioValue shouldBe true

        client.database
          .transactionOrDie(UserContactsQueries.getUserContacts(userID))
          .zioValue should contain theSameElementsAs upsertUserContacts.zipWithIndex.map {
          case (upsertUserContact, index) =>
            upsertUserContact
              .into[UserContactTable]
              .withFieldComputed(_.userContactID, _.userContactID.getOrElse(UserContactID.assume(s"${index + 1}")))
              .withFieldConst(_.userID, userID)
              .withFieldConst(_.createdAt, CreatedAt(now))
              .withFieldConst(_.updatedAt, UpdatedAt(now))
              .transform
        }
      }
    }
  }
}
