package io.mesazon.gateway.it

import cats.syntax.all.*
import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.WahaRepository
import io.mesazon.gateway.repository.domain.{WahaUserActivityRow, WahaUserMessageRow, WahaUserRow}
import io.mesazon.gateway.repository.queries.WahaQueries
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.{DockerComposeBase, ZWordSpecBase}
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

import PostgreSQLTestClient.PostgreSQLTestClientConfig

class WahaRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    wahaUsersTable = "waha_users",
    wahaUserActivityTable = "waha_user_activity",
    wahaUserMessagesTable = "waha_user_messages",
  )

  val repositoryConfigLive = ZLayer.succeed(repositoryConfig)

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
      client.checkIfTableExists(repositoryConfig.schema, repositoryConfig.wahaUsersTable).zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { client =>
    super.beforeEach()
    eventually {
      client.truncateTable(repositoryConfig.schema, repositoryConfig.wahaUsersTable).zioValue
    }
  }

  "WahaRepository" when {
    "insertWahaUser" should {
      "insert a waha user row" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live,
            repositoryConfigLive,
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val wahaUser = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        wahaUser shouldBe
          wahaUserRowRaw.copy(userID = UserID.assume("1"), createdAt = CreatedAt(now), updatedAt = UpdatedAt(now))
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
            WahaQueries.live,
            repositoryConfigLive,
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val result = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioEither

        result shouldBe Right(
          wahaUserRowRaw.copy(userID = UserID.assume("1"), createdAt = CreatedAt(now), updatedAt = UpdatedAt(now))
        )
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
            WahaQueries.live,
            repositoryConfigLive,
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
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
            WahaQueries.live,
            repositoryConfigLive,
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
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
      "upsert waha user activity force update true" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaQueries = ZIO
          .service[WahaQueries]
          .provide(WahaQueries.live, repositoryConfigLive)
          .zioValue

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live,
            repositoryConfigLive,
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val _ = wahaRepository
          .upsertWahaUserActivity(
            UserID.assume("1"),
            waha.MessageID.assume("1").some,
            isWaitingAssistantReply = true,
            forceUpdate = true,
          )
          .zioValue

        val wahaUserActivities = client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivities should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            lastMessageID = waha.MessageID.assume("1").some,
            isWaitingAssistantReply = true,
            lastUpdate = UpdatedAt(now),
          )
        )

        val _ = wahaRepository
          .upsertWahaUserActivity(
            UserID.assume("1"),
            waha.MessageID.assume("2").some,
            isWaitingAssistantReply = false,
            forceUpdate = true,
          )
          .zioValue

        val wahaUserActivitiesUpdate = client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivitiesUpdate should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            lastMessageID = waha.MessageID.assume("2").some,
            isWaitingAssistantReply = false,
            lastUpdate = UpdatedAt(now),
          )
        )

        val _ = wahaRepository
          .upsertWahaUserActivity(UserID.assume("1"), None, isWaitingAssistantReply = true, forceUpdate = true)
          .zioValue

        val wahaUserActivitiesUpdateNone =
          client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivitiesUpdateNone should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            lastMessageID = waha.MessageID.assume("2").some,
            isWaitingAssistantReply = true,
            lastUpdate = UpdatedAt(now),
          )
        )
      }

      "upsert waha user activity force update false" in withContext { (client: PostgreSQLTestClient) =>
        val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
        val wahaUserRowRaw = arbitrarySample[WahaUserRow]

        val wahaQueries = ZIO
          .service[WahaQueries]
          .provide(WahaQueries.live, repositoryConfigLive)
          .zioValue

        val wahaRepository = ZIO
          .service[WahaRepository]
          .provide(
            WahaRepository.live,
            ZLayer.succeed(client.database),
            WahaQueries.live,
            repositoryConfigLive,
            timeProviderMockLive(clockNow),
            idGeneratorMockLive,
          )
          .zioValue

        val _ = wahaRepository
          .createOrGetWahaUser(
            wahaUserRowRaw.wahaUserID,
            wahaUserRowRaw.fullName,
            wahaUserRowRaw.wahaAccountID,
            wahaUserRowRaw.wahaChatID,
            wahaUserRowRaw.phoneNumber,
          )
          .zioValue

        val _ = wahaRepository
          .upsertWahaUserActivity(
            UserID.assume("1"),
            waha.MessageID.assume("1").some,
            isWaitingAssistantReply = true,
            forceUpdate = true,
          )
          .zioValue

        val wahaUserActivities = client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivities should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            lastMessageID = waha.MessageID.assume("1").some,
            isWaitingAssistantReply = true,
            lastUpdate = UpdatedAt(now),
          )
        )

        val _ = wahaRepository
          .upsertWahaUserActivity(
            UserID.assume("1"),
            waha.MessageID.assume("1").some,
            isWaitingAssistantReply = false,
            forceUpdate = false,
          )
          .zioValue

        val wahaUserActivitiesUpdate = client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivitiesUpdate should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            lastMessageID = waha.MessageID.assume("1").some,
            isWaitingAssistantReply = false,
            lastUpdate = UpdatedAt(now),
          )
        )

        val _ = wahaRepository
          .upsertWahaUserActivity(
            UserID.assume("1"),
            waha.MessageID.assume("2").some,
            isWaitingAssistantReply = true,
            forceUpdate = false,
          )
          .zioValue

        val wahaUserActivitiesNoUpdate =
          client.database.transactionOrDie(wahaQueries.getAllWahaUserActivities).zioValue

        wahaUserActivitiesNoUpdate should contain theSameElementsAs Vector(
          WahaUserActivityRow(
            userID = UserID.assume("1"),
            lastMessageID = waha.MessageID.assume("1").some,
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
              WahaQueries.live,
              repositoryConfigLive,
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.fullName,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val _ = wahaRepository
            .upsertWahaUserActivity(
              UserID.assume("1"),
              waha.MessageID.assume("1").some,
              isWaitingAssistantReply = true,
              forceUpdate = true,
            )
            .zioValue

          val wahaUserActivity = wahaRepository.getUserWahaUserActivity(UserID.assume("1")).zioValue

          wahaUserActivity shouldBe Some(
            WahaUserActivityRow(
              userID = UserID.assume("1"),
              lastMessageID = waha.MessageID.assume("1").some,
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
            .provide(WahaQueries.live, repositoryConfigLive)
            .zioValue

          val wahaRepository = ZIO
            .service[WahaRepository]
            .provide(
              WahaRepository.live,
              ZLayer.succeed(client.database),
              WahaQueries.live,
              repositoryConfigLive,
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.fullName,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val messageID = waha.MessageID.assume("message-id")
          val message   = waha.MessageText.assume("Hello, World!")

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
            .provide(WahaQueries.live, repositoryConfigLive)
            .zioValue

          val wahaRepository = ZIO
            .service[WahaRepository]
            .provide(
              WahaRepository.live,
              ZLayer.succeed(client.database),
              WahaQueries.live,
              repositoryConfigLive,
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.fullName,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val messageID = waha.MessageID.assume("message-id")
          val message1  = waha.MessageText.assume("Hello, John!")
          val message2  = waha.MessageText.assume("Hello, Mike!")

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
              WahaQueries.live,
              repositoryConfigLive,
              timeProviderMockLive(clockNow),
              idGeneratorMockLive,
            )
            .zioValue

          val _ = wahaRepository
            .createOrGetWahaUser(
              wahaUserRowRaw.wahaUserID,
              wahaUserRowRaw.fullName,
              wahaUserRowRaw.wahaAccountID,
              wahaUserRowRaw.wahaChatID,
              wahaUserRowRaw.phoneNumber,
            )
            .zioValue

          val messageID1 = waha.MessageID.assume("message-id-1")
          val message1   = waha.MessageText.assume("Hello, John!")

          val messageID2 = waha.MessageID.assume("message-id-2")
          val message2   = waha.MessageText.assume("Hello, Mike!")

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

      "getWahaUsersActivityWaitingForAssistantReply" should {
        "get all waha users activity rows that waiting for assistant reply" in withContext {
          (client: PostgreSQLTestClient) =>
            val now      = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val clockNow = Clock.fixed(now, ZoneOffset.UTC)

            val wahaUserRowsRaw = arbitrarySample[WahaUserRow](5)

            val wahaRepository = ZIO
              .service[WahaRepository]
              .provide(
                WahaRepository.live,
                ZLayer.succeed(client.database),
                WahaQueries.live,
                repositoryConfigLive,
                timeProviderMockLive(clockNow),
                idGeneratorMockLive,
              )
              .zioValue

            ZIO
              .foreach(wahaUserRowsRaw)(row =>
                wahaRepository
                  .createOrGetWahaUser(
                    row.wahaUserID,
                    row.fullName,
                    row.wahaAccountID,
                    row.wahaChatID,
                    row.phoneNumber,
                  )
              )
              .zioValue

            val _ = wahaRepository
              .upsertWahaUserActivity(
                UserID.assume("1"),
                waha.MessageID.assume("1").some,
                isWaitingAssistantReply = true,
                forceUpdate = true,
              )
              .zioValue
            val _ = wahaRepository
              .upsertWahaUserActivity(
                UserID.assume("2"),
                waha.MessageID.assume("2").some,
                isWaitingAssistantReply = false,
                forceUpdate = true,
              )
              .zioValue
            val _ = wahaRepository
              .upsertWahaUserActivity(
                UserID.assume("3"),
                waha.MessageID.assume("3").some,
                isWaitingAssistantReply = true,
                forceUpdate = true,
              )
              .zioValue
            val _ = wahaRepository
              .upsertWahaUserActivity(
                UserID.assume("4"),
                waha.MessageID.assume("4").some,
                isWaitingAssistantReply = false,
                forceUpdate = true,
              )
              .zioValue
            val _ = wahaRepository
              .upsertWahaUserActivity(
                UserID.assume("5"),
                waha.MessageID.assume("5").some,
                isWaitingAssistantReply = true,
                forceUpdate = true,
              )
              .zioValue

            val wahaUsersActivityWaitingForAssistantReplyStream =
              wahaRepository.getWahaUsersActivityWaitingForAssistantReply.runCollect.zioValue

            wahaUsersActivityWaitingForAssistantReplyStream should contain theSameElementsAs Vector(
              WahaUserActivityRow(
                userID = UserID.assume("1"),
                lastMessageID = waha.MessageID("1").some,
                isWaitingAssistantReply = true,
                lastUpdate = UpdatedAt(now),
              ),
              WahaUserActivityRow(
                userID = UserID.assume("3"),
                lastMessageID = waha.MessageID("3").some,
                isWaitingAssistantReply = true,
                lastUpdate = UpdatedAt(now),
              ),
              WahaUserActivityRow(
                userID = UserID.assume("5"),
                lastMessageID = waha.MessageID("5").some,
                isWaitingAssistantReply = true,
                lastUpdate = UpdatedAt(now),
              ),
            )
        }
      }
    }
  }
}
