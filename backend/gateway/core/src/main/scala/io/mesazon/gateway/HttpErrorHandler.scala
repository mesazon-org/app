package io.mesazon.gateway

import io.mesazon.domain.gateway.{ServiceError, TapirServerError}
import io.mesazon.gateway.tapir.TapirTask
import zio.*

object HttpErrorHandler {

  private def logWarning(serviceError: ServiceError)(using trace: Trace): UIO[Unit] =
    for {
      fiberID <- ZIO.fiberId
      _       <- ZIO
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
      case error @ ServiceError.BadRequestError.ValidationError(invalidFields) =>
        logWarning(error)
          .as(smithy.ValidationError(fields = invalidFields.map(_.fieldName).toList))
      case error: ServiceError.BadRequestError =>
        logWarning(error)
          .as(smithy.BadRequest())
      case error: ServiceError.UnauthorizedError =>
        logError(error)
          .as(smithy.Unauthorized())
      case error: ServiceError.ForbiddenError =>
        logError(error)
          .as(smithy.Forbidden())
      case error: ServiceError.ConflictError =>
        logWarning(error)
          .as(smithy.Conflict())
      case error: ServiceError.InternalServerError =>
        logWarning(error)
          .as(smithy.InternalServerError())
      case error: ServiceError.ServiceUnavailableError =>
        logWarning(error)
          .as(smithy.ServiceUnavailable())
    }.catchSomeCause { case cause: Cause.Die =>
      ZIO
        .logErrorCause(s"$trace: Unexpected failure", cause)
        *> ZIO.fail(smithy.InternalServerError())
    }

  def errorResponseHandlerTapir[A](response: IO[ServiceError, A])(using trace: Trace): TapirTask[A] =
    response.flatMapError {
      case error: ServiceError.BadRequestError =>
        logWarning(error)
          .as(TapirServerError.BadRequestError)
      case error: ServiceError.UnauthorizedError =>
        logError(error)
          .as(TapirServerError.UnauthorizedError)
      case error: ServiceError.ForbiddenError =>
        logError(error)
          .as(TapirServerError.ForbiddenError)
      case error: ServiceError.ConflictError =>
        logWarning(error)
          .as(TapirServerError.ConflictError)
      case error: ServiceError.InternalServerError =>
        logWarning(error)
          .as(TapirServerError.InternalServerError)
      case error: ServiceError.ServiceUnavailableError =>
        logWarning(error)
          .as(TapirServerError.ServiceUnavailableError)
    }.catchSomeCause { case cause: Cause.Die =>
      ZIO
        .logErrorCause(s"$trace: Unexpected failure", cause)
        *> ZIO.fail(TapirServerError.InternalServerError)
    }
}
