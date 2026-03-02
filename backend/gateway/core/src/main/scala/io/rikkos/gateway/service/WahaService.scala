package io.rikkos.gateway.service

import io.mesazon.waha.WahaClient
import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha
import io.rikkos.domain.waha.input.ChattingSeenInput
import io.rikkos.gateway.repository.WahaRepository
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object WahaService {

  private final class WahaServiceImpl(repository: WahaRepository, wahaClient: WahaClient)
      extends smithy.WahaService[[A] =>> IO[ServiceError, A]] {

    /** HTTP POST /waha/webhook/message */
    override def wahaWebhookMessage(request: smithy.WahaMessageText): IO[ServiceError, Unit] =
      for {
        _ <- ZIO.logInfo(s"Received Waha webhook message: $request")
        messageID              = waha.MessageID.applyUnsafe(request.payload.id)
        maybeWahaUserAccountID =
          waha.UserAccountID
            .option(request.payload.from)
            .orElse(
              waha.UserAccountID.option(request.payload.data.info.sender)
            )
            .orElse(waha.UserAccountID.option(request.payload.data.info.senderAlt))
        maybeWhatsAppPhoneNumber =
          waha.WhatsAppPhoneNumber
            .option(request.payload.from)
            .orElse(
              waha.WhatsAppPhoneNumber.option(request.payload.data.info.sender)
            )
            .orElse(waha.WhatsAppPhoneNumber.option(request.payload.data.info.senderAlt))
        wahaUserAccountID = maybeWahaUserAccountID.getOrElse(
          waha.UserAccountID.fromWhatsAppPhoneNumber(maybeWhatsAppPhoneNumber.get)
        )
        wahaUserID =
          waha.UserID
            .option(request.payload.from)
            .orElse(waha.UserID.option(request.payload.data.info.sender))
            .orElse(waha.UserID.option(request.payload.data.info.senderAlt))
            .get
        wahaChatID  = waha.ChatID.fromUserAccountID(wahaUserAccountID)
        fullName    = FullName.applyUnsafe(request.payload.data.info.pushName)
        phoneNumber = waha.WahaPhone.fromUserAccountID(wahaUserAccountID)
        wahaUserRow <- repository
          .createOrGetWahaUser(
            wahaUserID,
            fullName,
            wahaUserAccountID,
            wahaChatID,
            PhoneNumber.applyUnsafe(phoneNumber.value),
          )
          .orDie
        _ <- repository
          .insertWahaUserMessage(wahaUserRow.userID, messageID, request.payload.body, isAssistant = false)
          .orDie
        _ <- repository
          .upsertWahaUserActivity(
            wahaUserRow.userID,
            Some(messageID),
            isWaitingAssistantReply = true,
            forceUpdate = true,
          )
          .orDie
        _ <- wahaClient
          .chattingSendSeen(
            ChattingSeenInput(
              sessionID = waha.SessionID.assume("testing"),
              chatID = wahaChatID,
              messageIDs = List(messageID),
            )
          )
          .orDie
      } yield ()

    /** HTTP POST /waha/webhook/typing/stop */
    override def wahaWebhookStopTyping(request: smithy.WahaStopTyping): IO[ServiceError, Unit] = ???

    /** HTTP POST /waha/webhook/typing/start */
    override def wahaWebhookStartTyping(request: smithy.WahaStartTyping): IO[ServiceError, Unit] = ???
  }

  private def observed(service: smithy.WahaService[[A] =>> IO[ServiceError, A]]): smithy.WahaService[Task] =
    new smithy.WahaService[Task] {
      override def wahaWebhookMessage(request: smithy.WahaMessageText): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.wahaWebhookMessage(request))

      override def wahaWebhookStopTyping(request: smithy.WahaStopTyping): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.wahaWebhookStopTyping(request))

      override def wahaWebhookStartTyping(request: smithy.WahaStartTyping): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.wahaWebhookStartTyping(request))
    }

  val live = ZLayer.derive[WahaServiceImpl] >>> ZLayer.fromFunction(observed)
}
