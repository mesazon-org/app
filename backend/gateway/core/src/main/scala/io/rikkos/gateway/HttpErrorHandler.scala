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

  private def logThrowable(throwable: Throwable)(using trace: Trace): UIO[Unit] =
    ZIO.fiberId.flatMap(fid =>
      ZIO
        .logErrorCause(
          s"$trace: Unexpected error ${throwable.getMessage}",
          Cause.die(throwable, StackTrace.fromJava(fid, throwable.getStackTrace)),
        )
    )

  def errorResponseHandler[A](response: Task[A])(using trace: Trace): Task[A] =
    response.flatMapError {
      case error: ServiceError.BadRequestError =>
        logWarning(error)
          .map(_ => smithy.BadRequest())
      case error: ServiceError.UnauthorizedError =>
        logError(error)
          .map(_ => smithy.Unauthorized())
      case error: Throwable =>
        logThrowable(error)
          .map(_ => smithy.InternalServerError())
    }.catchSomeCause { case cause: Cause.Die =>
      ZIO
        .logErrorCause(s"$trace: unexpected failure", cause)
        .flatMap(_ => ZIO.fail(smithy.InternalServerError()))
    }
}
