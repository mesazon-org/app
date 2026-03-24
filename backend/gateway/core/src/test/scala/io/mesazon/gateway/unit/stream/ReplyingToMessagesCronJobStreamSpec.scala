package io.mesazon.gateway.unit.stream

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.ReplyingToMessagesCronJobConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.{WahaUserActivityRow, WahaUserMessageRow, WahaUserRow}
import io.mesazon.gateway.stream.ReplyingToMessagesCronJobStream
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.testkit.base.{GatewayArbitraries, ZWordSpecBase}
import zio.{Clock as _, *}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class ReplyingToMessagesCronJobStreamSpec extends ZWordSpecBase, RepositoryArbitraries, GatewayArbitraries {

  "ReplyingToMessagesCronJobStream" should {
    "stream" in new TestContext {
      val now               = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      val clock             = Clock.fixed(now, ZoneOffset.UTC)
      val assistantResponse = arbitrarySample[AssistantResponse]

      val wahaUserRow1 = arbitrarySample[WahaUserRow]
      val wahaUserRow2 = arbitrarySample[WahaUserRow]
      val wahaUserRow3 = arbitrarySample[WahaUserRow]
      val wahaUserRow4 = arbitrarySample[WahaUserRow]

      // last update is 5 seconds ago, so it should be included in the stream
      val wahaUserActivityRow1 = arbitrarySample[WahaUserActivityRow]
        .copy(
          userID = wahaUserRow1.userID,
          isWaitingAssistantReply = true,
          lastUpdate = UpdatedAt.assume(now.minusSeconds(replyingToMessagesCronJobConfig.lastUpdateOffsetSeconds + 1)),
        )

      // last update is 4 seconds ago, so it should not be included in the stream
      val wahaUserActivityRow2 = arbitrarySample[WahaUserActivityRow]
        .copy(
          userID = wahaUserRow2.userID,
          isWaitingAssistantReply = true,
          lastUpdate = UpdatedAt.assume(now.minusSeconds(replyingToMessagesCronJobConfig.lastUpdateOffsetSeconds)),
        )

      // isWaitingAssistantReply is false, so it should not be included in the stream
      val wahaUserActivityRow3 = arbitrarySample[WahaUserActivityRow]
        .copy(
          userID = wahaUserRow3.userID,
          isWaitingAssistantReply = false,
          lastUpdate = UpdatedAt.assume(now.minusSeconds(replyingToMessagesCronJobConfig.lastUpdateOffsetSeconds + 1)),
        )

      val wahaUserMessageRows =
        arbitrarySample[WahaUserMessageRow](replyingToMessagesCronJobConfig.maxNumberContextMessages + 2)
          .map(_.copy(userID = wahaUserRow1.userID))

      val replyMessagesStream = buildReplyMessagesStream(
        assistantResponse = assistantResponse,
        clock = clock,
        wahaUserRows = Map(
          wahaUserRow1.userID -> wahaUserRow1,
          wahaUserRow2.userID -> wahaUserRow2,
          wahaUserRow3.userID -> wahaUserRow3,
          wahaUserRow4.userID -> wahaUserRow4,
        ),
        wahaUserActivityRows = Map(
          wahaUserActivityRow1.userID -> wahaUserActivityRow1,
          wahaUserActivityRow2.userID -> wahaUserActivityRow2,
          wahaUserActivityRow3.userID -> wahaUserActivityRow3,
        ),
        wahaUserMessageRows = Map(wahaUserRow1.userID -> wahaUserMessageRows.toList),
      )

      replyMessagesStream.stream.runHead.zioValue

      sendMessageCounterRef.get.zioValue shouldBe 1
      messagesCounterRef.get.zioValue shouldBe 40

      chattingSendSeenCounterRef.get.zioValue shouldBe 1
      chattingSendMessageCounterRef.get.zioValue shouldBe 1

      insertWahaUserMessageCounterRef.get.zioValue shouldBe 1
      getWahaUserCounterRef.get.zioValue shouldBe 1
      getWahaUserMessagesCounterRef.get.zioValue shouldBe 1
      getWahaUsersActivityWaitingForAssistantReplyCounterRef.get.zioValue shouldBe 1
    }
  }

  trait TestContext {
    val chattingSendMessageCounterRef                          = Ref.make(0).zioValue
    val chattingSendSeenCounterRef                             = Ref.make(0).zioValue
    val messagesCounterRef                                     = Ref.make(0).zioValue
    val sendMessageCounterRef                                  = Ref.make(0).zioValue
    val insertWahaUserMessageCounterRef                        = Ref.make(0).zioValue
    val getWahaUserCounterRef                                  = Ref.make(0).zioValue
    val getWahaUserMessagesCounterRef                          = Ref.make(0).zioValue
    val getWahaUsersActivityWaitingForAssistantReplyCounterRef = Ref.make(0).zioValue

    val replyingToMessagesCronJobConfig = ReplyingToMessagesCronJobConfig(
      triggerEverySeconds = 1,
      lastUpdateOffsetSeconds = 4,
      maxNumberContextMessages = 40,
    )

    def buildReplyMessagesStream(
        config: ReplyingToMessagesCronJobConfig = replyingToMessagesCronJobConfig,
        assistantResponse: AssistantResponse,
        wahaUserRows: Map[UserID, WahaUserRow] = Map.empty,
        wahaUserActivityRows: Map[UserID, WahaUserActivityRow] = Map.empty,
        wahaUserMessageRows: Map[UserID, List[WahaUserMessageRow]] = Map.empty,
        clock: Clock = Clock.systemUTC(),
    ): ReplyingToMessagesCronJobStream =
      ZIO
        .service[ReplyingToMessagesCronJobStream]
        .provide(
          ReplyingToMessagesCronJobStream.live,
          ZLayer.succeed(config),
          wahaClientMockLive(
            chattingSendSeenCounterRef = chattingSendSeenCounterRef,
            chattingSendMessageCounterRef = chattingSendMessageCounterRef,
          ),
          openAIClientMockLive(
            assistantResponse = assistantResponse,
            sendMessageCounterRef = sendMessageCounterRef,
            messagesCounterRef = messagesCounterRef,
          ),
          wahaRepositoryMockLive(
            wahaUserRows = wahaUserRows,
            wahaUserActivityRows = wahaUserActivityRows,
            wahaUserMessageRows = wahaUserMessageRows,
            getWahaUserCounterRef = getWahaUserCounterRef,
            insertWahaUserMessageCounterRef = insertWahaUserMessageCounterRef,
            getWahaUsersActivityWaitingForAssistantReplyCounterRef =
              getWahaUsersActivityWaitingForAssistantReplyCounterRef,
            getWahaUserMessagesCounterRef = getWahaUserMessagesCounterRef,
          ),
          timeProviderMockLive(clock),
        )
        .zioValue
  }
}
