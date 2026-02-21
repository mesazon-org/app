package io.rikkos.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.repository.WahaRepository
import io.rikkos.gateway.repository.domain.{WahaUserActivityRow, WahaUserMessageRow, WahaUserRow}
import io.rikkos.gateway.repository.queries.WahaQueries
import io.rikkos.gateway.utils.RepositoryArbitraries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.{DockerComposeBase, ZWordSpecBase}
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class WahaRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val schema        = "local_schema"
  val wahaUserTable = "waha_users"

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
      client.checkIfTableExists(schema, wahaUserTable).zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { client =>
    super.beforeEach()
    eventually {
      client.truncateTable(schema, wahaUserTable).zioValue
    }
  }

  "WahaRepository" when {
    "insertWahaUser" should {
      "insert a waha user row" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaQueries = ZIO
          .service[WahaQueries]
          .provide(WahaQueries.live(schema))
          .zioValue

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live(schema),
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val wahaUsers = client.database.transactionOrDie(wahaQueries.getAllWahaUsers).zioValue

        wahaUsers should contain theSameElementsAs Vector(
          wahaUserRowRaw.copy(userID = UserID.assume("1"), createdAt = CreatedAt(now), updatedAt = UpdatedAt(now))
        )
      }

      "not fail to insert the same waha user row on user id" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live(schema),
            timeProviderMockLive(clockNow),
            idGeneratorMockConstLive("id"),
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val result = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioEither

        assert(result.isRight)
      }
    }

    "getWahaUser" should {
      "get waha user using domain user id" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live(schema),
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val wahaUser = wahaRepository.getWahaUser(UserID.assume("1")).zioValue

        wahaUser shouldBe Some(
          wahaUserRowRaw.copy(userID = UserID.assume("1"), createdAt = CreatedAt(now), updatedAt = UpdatedAt(now))
        )
      }
    }

    "getWahaUserWithWahaUserId" should {
      "get waha user using waha user id" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live(schema),
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val wahaUser = wahaRepository.getWahaUserWithWahaUserId(wahaUserRowRaw.wahaUserID).zioValue

        wahaUser shouldBe Some(
          wahaUserRowRaw.copy(userID = UserID.assume("1"), createdAt = CreatedAt(now), updatedAt = UpdatedAt(now))
        )
      }
    }

    "upsertWahaUserActivity" should {
      "upsert waha user activity" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaQueries = ZIO
          .service[WahaQueries]
          .provide(WahaQueries.live(schema))
          .zioValue

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live(schema),
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val _ = wahaRepository.upsertWahaUserActivity(UserID.assume("1"), isWaitingAssistantReply = true).zioValue

        val wahaUserActivities = client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivities should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            isWaitingAssistantReply = true,
            lastUpdate = UpdatedAt(now),
          )
        )

        val _ = wahaRepository.upsertWahaUserActivity(UserID.assume("1"), isWaitingAssistantReply = false).zioValue

        val wahaUserActivitiesUpdate = client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivitiesUpdate should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            isWaitingAssistantReply = false,
            lastUpdate = UpdatedAt(now),
          )
        )
      }

      "getUserWahaUserActivity" should {
        "get user waha activity" in withContext { (client: PostgreSQLTestClient) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
          val wahaUserRowRaw = arbitrarySample[WahaUserRow]

          val wahaRepository = ZIO
            .service[WahaRepository]
            .provide(
              WahaRepository.live,
              ZLayer.succeed(client.database),
              WahaQueries.live(schema),
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val _ = wahaRepository.upsertWahaUserActivity(UserID.assume("1"), isWaitingAssistantReply = true).zioValue

          val wahaUserActivity = wahaRepository.getUserWahaUserActivity(UserID.assume("1")).zioValue

          wahaUserActivity shouldBe Some(
            WahaUserActivityRow(
              userID = UserID.assume("1"),
              isWaitingAssistantReply = true,
              lastUpdate = UpdatedAt(now),
            )
          )
        }
      }

      "insertWahaUserMessage" should {
        "insert waha user message" in withContext { (client: PostgreSQLTestClient) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
          val wahaUserRowRaw = arbitrarySample[WahaUserRow]

          val wahaQueries = ZIO
            .service[WahaQueries]
            .provide(WahaQueries.live(schema))
            .zioValue

          val wahaRepository = ZIO
            .service[WahaRepository]
            .provide(
              WahaRepository.live,
              ZLayer.succeed(client.database),
              WahaQueries.live(schema),
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val messageID = waha.MessageID.assume("message-id")
          val message   = "Hello, World!"

          val _ = wahaRepository
            .insertWahaUserMessage(UserID.assume("1"), messageID, message, isAssistant = false)
            .zioValue

          val wahaUserMessages = client.database.transactionOrDie(wahaQueries.getAllWahaUserMessages).zioValue

          wahaUserMessages should contain theSameElementsAs Vector(
            WahaUserMessageRow(
              userID = UserID.assume("1"),
              messageID = messageID,
              message = message,
              isAssistant = false,
              createdAt = CreatedAt(now),
            )
          )
        }

        "not fail to insert conflict user waha message" in withContext { (client: PostgreSQLTestClient) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
          val wahaUserRowRaw = arbitrarySample[WahaUserRow]

          val wahaQueries = ZIO
            .service[WahaQueries]
            .provide(WahaQueries.live(schema))
            .zioValue

          val wahaRepository = ZIO
            .service[WahaRepository]
            .provide(
              WahaRepository.live,
              ZLayer.succeed(client.database),
              WahaQueries.live(schema),
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val messageID = waha.MessageID.assume("message-id")
          val message1  = "Hello, John!"
          val message2  = "Hello, Mike!"

          val _ = wahaRepository
            .insertWahaUserMessage(UserID.assume("1"), messageID, message1, isAssistant = false)
            .zioValue

          val _ = wahaRepository
            .insertWahaUserMessage(UserID.assume("1"), messageID, message2, isAssistant = true)
            .zioEither

          val wahaUserMessages = client.database.transactionOrDie(wahaQueries.getAllWahaUserMessages).zioValue

          wahaUserMessages should contain theSameElementsAs Vector(
            WahaUserMessageRow(
              userID = UserID.assume("1"),
              messageID = messageID,
              message = message1,
              isAssistant = false,
              createdAt = CreatedAt(now),
            )
          )
        }
      }

      "getWahaUserMessages" should {
        "get waha user messages" in withContext { (client: PostgreSQLTestClient) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
          val wahaUserRowRaw = arbitrarySample[WahaUserRow]

          val wahaRepository = ZIO
            .service[WahaRepository]
            .provide(
              WahaRepository.live,
              ZLayer.succeed(client.database),
              WahaQueries.live(schema),
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val messageID1 = waha.MessageID.assume("message-id-1")
          val message1   = "Hello, John!"

          val messageID2 = waha.MessageID.assume("message-id-2")
          val message2   = "Hello, Mike!"

          val _ = wahaRepository
            .insertWahaUserMessage(UserID.assume("1"), messageID1, message1, isAssistant = false)
            .zioValue

          val _ = wahaRepository
            .insertWahaUserMessage(UserID.assume("1"), messageID2, message2, isAssistant = true)
            .zioValue

          val wahaUserMessagesStream = wahaRepository.getWahaUserMessages(UserID.assume("1")).runCollect.zioValue

          wahaUserMessagesStream should contain theSameElementsInOrderAs Chunk(
            WahaUserMessageRow(
              userID = UserID.assume("1"),
              messageID = messageID1,
              message = message1,
              isAssistant = false,
              createdAt = CreatedAt(now),
            ),
            WahaUserMessageRow(
              userID = UserID.assume("1"),
              messageID = messageID2,
              message = message2,
              isAssistant = true,
              createdAt = CreatedAt(now),
            ),
          )
        }
      }
    }
  }
}
