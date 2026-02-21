package io.rikkos.gateway.service

import io.rikkos.domain.gateway.{ServiceError, UserID}
import io.rikkos.gateway.repository.WahaRepository
import io.rikkos.gateway.smithy
import zio.*

object WahaService {

  final case class WahaMessageText()
  final case class WahaStartTyping()
  final case class WahaStopTyping()

  private final class WahaServiceImpl(val repository: WahaRepository)
      extends smithy.WahaService[[A] =>> IO[ServiceError, A]] {

    /** HTTP POST /waha/webhook/message */
    override def wahaWebhookMessage(request: smithy.WahaMessageText): IO[ServiceError, Unit] =
      for {
        _ <- repository.getWahaUser(UserID.assume("dsd")).orDie
        _ <- ZIO.logInfo(s"Received Waha webhook message: ${request}")
      } yield ()

    /** HTTP POST /waha/webhook/typing/stop */
    override def wahaWebhookStopTyping(request: smithy.WahaStopTyping): IO[ServiceError, Unit] = ???

    /** HTTP POST /waha/webhook/typing/start */
    override def wahaWebhookStartTyping(request: smithy.WahaStartTyping): IO[ServiceError, Unit] = ???
  }

  def observed(service: smithy.WahaService[[A] =>> IO[ServiceError, A]]): smithy.WahaService[Task] =
    new smithy.WahaService[Task] {
      override def wahaWebhookMessage(request: smithy.WahaMessageText): Task[Unit] =
        ZIO.logDebug(s"Received Waha webhook message: ${request}") *> service.wahaWebhookMessage(request)

      override def wahaWebhookStopTyping(request: smithy.WahaStopTyping): Task[Unit] =
        ZIO.logDebug(s"Received Waha webhook stop typing: ${request}") *> service.wahaWebhookStopTyping(request)

      override def wahaWebhookStartTyping(request: smithy.WahaStartTyping): Task[Unit] =
        ZIO.logDebug(s"Received Waha webhook start typing: ${request}") *> service.wahaWebhookStartTyping(request)
    }

  val _ = ZLayer.derive[WahaServiceImpl] >>> ZLayer.fromFunction(observed)

}
