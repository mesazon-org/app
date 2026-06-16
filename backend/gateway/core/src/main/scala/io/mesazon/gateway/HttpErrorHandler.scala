package io.mesazon.gateway

import io.mesazon.domain.gateway.{ServiceError, TapirServerError}
import io.mesazon.gateway.tapir.TapirTask
import sttp.model.StatusCode
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
          .as((StatusCode.BadRequest, TapirServerError("BAD_REQUEST_ERROR", "Bad request")))
      case error: ServiceError.UnauthorizedError =>
        logError(error)
          .as((StatusCode.Unauthorized, TapirServerError("UNAUTHORIZED_ERROR", "Unauthorized")))
      case error: ServiceError.InternalServerError =>
        logWarning(error)
          .as((StatusCode.InternalServerError, TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error")))
      case error: ServiceError.ServiceUnavailableError =>
        logWarning(error)
          .as((StatusCode.ServiceUnavailable, TapirServerError("SERVICE_UNAVAILABLE_ERROR", "Service unavailable")))
    }.catchSomeCause { case cause: Cause.Die =>
      ZIO
        .logErrorCause(s"$trace: Unexpected failure", cause)
        *> ZIO.fail((StatusCode.Unauthorized, TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error")))
    }
}
