package io.mesazon.domain.gateway

// `schemaName` is the OpenAPI component name, kept identical to the smithy4s-generated
// `<Structure>ResponseContent` schemas so both swagger docs expose consistent names.
enum TapirServerError(val code: String, val message: String, val schemaName: String) {
  // 400 Bad Request
  case BadRequestError extends TapirServerError("BAD_REQUEST_ERROR", "Bad request", "BadRequestResponseContent")

  // 401 Unauthorized
  case UnauthorizedError extends TapirServerError("UNAUTHORIZED_ERROR", "Unauthorized", "UnauthorizedResponseContent")

  // 404 Not Found
  case NotFoundError extends TapirServerError("NOT_FOUND_ERROR", "Not found", "NotFoundResponseContent")

  // 500 Internal Server Error
  case InternalServerError
      extends TapirServerError("INTERNAL_SERVER_ERROR", "Internal server error", "InternalServerErrorResponseContent")

  // 503 Service Unavailable
  case ServiceUnavailableError
      extends TapirServerError("SERVICE_UNAVAILABLE_ERROR", "Service unavailable", "ServiceUnavailableResponseContent")
}
