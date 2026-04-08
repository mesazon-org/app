package io.mesazon.gateway.stream

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.{AssistantResponse, ServiceError}
import io.mesazon.domain.waha
import io.mesazon.gateway.clients.OpenAIClient
import io.mesazon.gateway.config.ReplyingToMessagesCronJobConfig
import io.mesazon.gateway.json.given
import io.mesazon.gateway.repository.WahaRepository
import io.mesazon.waha.WahaClient
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}
import zio.*
import zio.stream.*

import waha.input.{ChattingMessageInput, ChattingSeenInput}

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
        _               <- ZStream.logDebug("Scheduled reply to messages ...")
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
        _ <- ZStream.logDebug("Scheduled reply messages finished.")
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
