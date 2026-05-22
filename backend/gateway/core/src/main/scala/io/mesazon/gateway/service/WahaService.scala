package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.WahaRepository
import io.mesazon.gateway.validation.service.WahaServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object WahaService {

  private final class WahaServiceImpl(
      wahaRepository: WahaRepository,
      wahaServiceValidator: WahaServiceValidator,
  ) extends smithy.WahaService[ServiceTask] {

    /** HTTP POST /waha/webhook/message */
    override def wahaWebhookMessage(request: smithy.WahaMessageTextRequest): IO[ServiceError, Unit] =
      for {
        _           <- ZIO.logInfo(s"Received Waha webhook message: $request")
        wahaMessage <- wahaServiceValidator.validate(request)
        wahaUserRow <- wahaRepository
          .createOrGetWahaUser(
            wahaMessage.wahaUserID,
            wahaMessage.wahaFullName,
            wahaMessage.wahaUserAccountID,
            wahaMessage.wahaChatID,
            wahaMessage.phoneNumber,
          )
          .orDie
        _ <- wahaRepository
          .insertWahaUserMessage(
            wahaUserRow.userID,
            wahaMessage.wahaMessageID,
            wahaMessage.wahaMessageText,
            isAssistant = false,
          )
          .orDie
        _ <- wahaRepository
          .upsertWahaUserActivity(
            wahaUserRow.userID,
            Some(wahaMessage.wahaMessageID),
            isWaitingAssistantReply = true,
            forceUpdate = true,
          )
          .orDie
      } yield ()
  }

  private def observed(service: smithy.WahaService[ServiceTask]): smithy.WahaService[Task] =
    new smithy.WahaService[Task] {
      override def wahaWebhookMessage(request: smithy.WahaMessageTextRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.wahaWebhookMessage(request))
    }

  val live = ZLayer.derive[WahaServiceImpl] >>> ZLayer.fromFunction(observed)
}
