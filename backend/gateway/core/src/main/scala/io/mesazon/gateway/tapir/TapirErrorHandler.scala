package io.mesazon.gateway.tapir

import io.mesazon.domain.gateway.{ServiceError, TapirServerError}
import sttp.model.StatusCode
import zio.*

object TapirErrorHandler {

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

  def handleError[A](response: IO[ServiceError, A])(using trace: Trace): IO[(StatusCode, TapirServerError), A] =
    response.flatMapError {
      case error: ServiceError.BadRequestError =>
        logWarning(error)
          .as(
            (
              StatusCode.BadRequest,
              TapirServerError("BAD_REQUEST_ERROR", "Bad request."),
            )
          )
      case error: ServiceError.UnauthorizedError =>
        logError(error)
          .as(
            (
              StatusCode.Unauthorized,
              TapirServerError("UNAUTHORIZED_ERROR", "Unauthorized connection."),
            )
          )
      case error: ServiceError.InternalServerError =>
        logError(error)
          .as(
            (
              StatusCode.InternalServerError,
              TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error."),
            )
          )
      case error: ServiceError.ServiceUnavailableError =>
        ZIO.succeed((StatusCode.ServiceUnavailable, TapirServerError("ServiceUnavailable", error.message)))
    }.catchSomeCause { case cause: Cause.Die =>
      ZIO
        .logErrorCause(s"$trace: Unexpected failure", cause)
        *> ZIO.fail(
          (StatusCode.InternalServerError, TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error."))
        )
    }
}
