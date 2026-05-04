$version: "2.0"
namespace io.mesazon.gateway.smithy

list InvalidFields {
    member: String
}

@error("client")
@httpError(400)
structure BadRequest {
    @required
    code: String = "BAD_REQUEST_ERROR"
    @required
    message: String = "Bad request"
}

@error("client")
@httpError(400)
structure ValidationError {
    @required
    code: String = "VALIDATION_ERROR"
    @required
    message: String = "Validation error"
    @required
    fields: InvalidFields
}

@error("client")
@httpError(401)
structure Unauthorized {
    @required
    code: String = "UNAUTHORIZED_ERROR"
    @required
    message: String = "Unauthorized connection."
}

@error("client")
@httpError(409)
structure Conflict {
    @required
    code: String = "CONFLICT_ERROR"
    @required
    message: String = "Conflict request"
}

@error("client")
@httpError(429)
structure TooManyRequests {
    @required
    code: String = "TOO_MANY_REQUESTS_ERROR"
    @required
    message: String = "Too many requests"
    @required
    blockDurationSeconds: Long
}

@error("server")
@httpError(500)
structure InternalServerError {
    @required
    code: String = "INTERNAL_SERVER_ERROR"
    @required
    message: String = "Internal server error"
}

@error("server")
@httpError(503)
structure ServiceUnavailable {
    @required
    code: String = "SERVICE_UNAVAILABLE_ERROR"
    @required
    message: String = "The server is currently unavailable"
}
