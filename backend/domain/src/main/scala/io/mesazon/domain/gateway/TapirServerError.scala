package io.mesazon.domain.gateway

sealed abstract class TapirServerError(val code: String, val message: String)

object TapirServerError {
  // 400 Bad Request
  case object BadRequestError extends TapirServerError("BAD_REQUEST_ERROR", "Bad request")

  // 401 Unauthorized
  case object UnauthorizedError extends TapirServerError("UNAUTHORIZED_ERROR", "Unauthorized")

  // 404 Not Found
  case object NotFoundError extends TapirServerError("NOT_FOUND_ERROR", "Not found")

  // 500 Internal Server Error
  case object InternalServerError extends TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error")

  // 503 Service Unavailable
  case object ServiceUnavailableError extends TapirServerError("SERVICE_UNAVAILABLE_ERROR", "Service unavailable")
}
