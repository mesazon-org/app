package io.mesazon.domain.gateway

enum TapirServerError(val code: String, val message: String) {
  // 400 Bad Request
  case BadRequestError extends TapirServerError("BAD_REQUEST_ERROR", "Bad request")

  // 401 Unauthorized
  case UnauthorizedError extends TapirServerError("UNAUTHORIZED_ERROR", "Unauthorized")

  // 404 Not Found
  case NotFoundError extends TapirServerError("NOT_FOUND_ERROR", "Not found")

  // 500 Internal Server Error
  case InternalServerError extends TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error")

  // 503 Service Unavailable
  case ServiceUnavailableError extends TapirServerError("SERVICE_UNAVAILABLE_ERROR", "Service unavailable")
}
