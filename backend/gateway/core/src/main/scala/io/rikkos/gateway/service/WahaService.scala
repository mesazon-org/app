package io.rikkos.gateway.service

import io.mesazon.domain.gateway.*
import io.rikkos.gateway.repository.WahaRepository
import io.rikkos.gateway.validation.ServiceValidator
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object WahaService {

  private final class WahaServiceImpl(
      repository: WahaRepository,
      wahaRequestValidator: ServiceValidator[smithy.WahaMessageTextRequest, WahaMessage],
  ) extends smithy.WahaService[[A] =>> IO[ServiceError, A]] {

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

  private def observed(service: smithy.WahaService[[A] =>> IO[ServiceError, A]]): smithy.WahaService[Task] =
    new smithy.WahaService[Task] {
      override def wahaWebhookMessage(request: smithy.WahaMessageTextRequest): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.wahaWebhookMessage(request))
    }

  val live = ZLayer.derive[WahaServiceImpl] >>> ZLayer.fromFunction(observed)
}
