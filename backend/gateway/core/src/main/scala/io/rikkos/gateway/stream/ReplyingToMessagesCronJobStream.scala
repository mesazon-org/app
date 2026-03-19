package io.rikkos.gateway.stream

import io.mesazon.waha.WahaClient
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.gateway.{AssistantResponse, ServiceError}
import io.rikkos.domain.waha
import io.rikkos.domain.waha.input.{ChattingMessageInput, ChattingSeenInput}
import io.rikkos.gateway.clients.OpenAIClient
import io.rikkos.gateway.config.ReplyingToMessagesCronJobConfig
import io.rikkos.gateway.json.given
import io.rikkos.gateway.repository.WahaRepository
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}
import zio.*
import zio.stream.*

trait ReplyingToMessagesCronJobStream {
  def stream: Stream[Throwable, Unit]
}

object ReplyingToMessagesCronJobStream {

  private final class ReplyingToMessagesCronJobStreamImpl(
      config: ReplyingToMessagesCronJobConfig,
      wahaClient: WahaClient,
      repository: WahaRepository,
      timeProvider: TimeProvider,
      openAIClient: OpenAIClient,
  ) extends ReplyingToMessagesCronJobStream {

    private def replyToMessages(): Stream[Throwable, Unit] =
      for {
        _               <- ZStream.logInfo("Scheduled reply to messages ...")
        userActivityRow <- repository.getWahaUsersActivityWaitingForAssistantReply
          .filterZIO(row =>
            timeProvider.instantNow.map(_.isAfter(row.lastUpdate.value.plusSeconds(config.lastUpdateOffsetSeconds)))
          )
        _             <- ZStream.logDebug(s"Replying to user activity row: [$userActivityRow]")
        maybeWahaUser <- ZStream.fromZIO(repository.getWahaUser(userActivityRow.userID))
        _             <- ZStream.logDebug(s"Got waha user: [$maybeWahaUser]")
        wahaUser      <- ZStream.fromZIO(
          ZIO.getOrFailWith(
            ServiceError.InternalServerError
              .UnexpectedError(s"No Waha user found for user activity row [$userActivityRow]", None)
          )(maybeWahaUser)
        )
        userMessages <- ZStream.fromZIO(
          repository.getWahaUserMessages(userActivityRow.userID).take(config.maxNumberContextMessages).runCollect
        )
        maybeLastUserMessage = userMessages.headOption
        _ <- ZStream.fromZIO(
          ZIO.foreach(maybeLastUserMessage)(lastUserMessage =>
            wahaClient
              .chattingSendSeen(
                ChattingSeenInput(
                  sessionID = waha.SessionID.assume("testing"),
                  chatID = wahaUser.wahaChatID,
                  messageIDs = List(lastUserMessage.messageID),
                )
              )
              .orDie
          )
        )
        assistantResponse <- ZStream.fromZIO(
          openAIClient.sendMessage[AssistantResponse](
            userMessages.map(row =>
              if (row.isAssistant) Message.AssistantMessage(content = row.message.value)
              else Message.UserMessage(content = Content.TextContent(row.message.value))
            )
          )
        )
        chattingSendMessageOutput <- ZStream.fromZIO(
          wahaClient
            .chattingSendMessage(
              ChattingMessageInput.Text(
                sessionID = waha.SessionID.assume("testing"),
                chatID = wahaUser.wahaChatID,
                text = assistantResponse.message,
                linkPreview = None,
                linkPreviewHighQuality = None,
                replyToMessageID = None,
              )
            )
        )
        _ <- ZStream.fromZIO(
          repository.insertWahaUserMessage(
            wahaUser.userID,
            chattingSendMessageOutput.messageID,
            assistantResponse.message,
            isAssistant = true,
          )
        )
        _ <- ZStream.fromZIO(
          repository.upsertWahaUserActivity(
            wahaUser.userID,
            userActivityRow.lastMessageID,
            isWaitingAssistantReply = false,
            forceUpdate = false,
          )
        )
        _ <- ZStream.logInfo("Scheduled reply messages finished.")
      } yield ()

    override def stream: Stream[Throwable, Unit] =
      ZStream.repeatZIOWithSchedule(
        replyToMessages().runDrain,
        Schedule.fixed(config.triggerEverySeconds.seconds),
      )
  }

  private def observed(stream: ReplyingToMessagesCronJobStream): ReplyingToMessagesCronJobStream = stream

  val live = ZLayer.derive[ReplyingToMessagesCronJobStreamImpl] >>> ZLayer.fromFunction(observed)
}
