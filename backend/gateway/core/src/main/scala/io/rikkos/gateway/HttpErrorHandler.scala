package io.rikkos.gateway

import io.rikkos.domain.ServiceError
import zio.*

object HttpErrorHandler {

  private def logWarning(serviceError: ServiceError)(using trace: Trace): UIO[Unit] =
    for {
      fiberID <- ZIO.fiberId
      _ <- ZIO
        .logWarningCause(
          s"$trace: ${serviceError.message}",
          Cause.die(serviceError, StackTrace.fromJava(fiberID, serviceError.getStackTrace)),
        )
    } yield ()

  private def logError(serviceError: ServiceError)(using trace: Trace): UIO[Unit] =
    ZIO.fiberId.flatMap(fid =>
      ZIO
        .logErrorCause(
          s"$trace: ${serviceError.message}",
          Cause.die(serviceError, StackTrace.fromJava(fid, serviceError.getStackTrace)),
        )
    )

  def errorResponseHandler[A](response: IO[ServiceError, A])(using trace: Trace): Task[A] =
    response.flatMapError {
      case error: ServiceError.BadRequestError =>
        logWarning(error)
          .map(_ => smithy.BadRequest())
      case error: ServiceError.UnauthorizedError =>
        logError(error)
          .map(_ => smithy.Unauthorized())
      case error: ServiceError.ConflictError =>
        logWarning(error)
          .map(_ => smithy.Conflict())
    }.catchSomeCause { case cause: Cause.Die =>
      ZIO
        .logErrorCause(s"$trace: Unexpected failure", cause)
        .flatMap(_ => ZIO.fail(smithy.InternalServerError()))
    }
}
