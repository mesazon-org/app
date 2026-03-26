package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.WahaRepository
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object WahaService {

  private final class WahaServiceImpl(
      repository: WahaRepository,
      wahaRequestValidator: ServiceValidator[smithy.WahaMessageTextRequest, WahaMessage],
  ) extends smithy.WahaService[ServiceTask] {

    /** HTTP POST /waha/webhook/message */
    override def wahaWebhookMessage(request: smithy.WahaMessageTextRequest): IO[ServiceError, Unit] =
      for {
        _           <- ZIO.logInfo(s"Received Waha webhook message: $request")
        wahaMessage <- wahaRequestValidator.validate(request)
        wahaUserRow <- repository
          .createOrGetWahaUser(
            wahaMessage.wahaUserID,
            wahaMessage.wahaFullName,
            wahaMessage.wahaUserAccountID,
            wahaMessage.wahaChatID,
            wahaMessage.phoneNumber,
          )
          .orDie
        _ <- repository
          .insertWahaUserMessage(
            wahaUserRow.userID,
            wahaMessage.wahaMessageID,
            wahaMessage.wahaMessageText,
            isAssistant = false,
          )
          .orDie
        _ <- repository
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
