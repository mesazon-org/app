$version: "2.0"
namespace io.rikkos.gateway.smithy

@error("client")
@httpError(400)
structure BadRequest {
    @required
    code: Integer = 400
    @required
    message: String = "Bad request."
}

@error("client")
@httpError(401)
structure Unauthorized {
    @required
    code: Integer = 401
    @required
    message: String = "Unauthorized connection."
}

@error("client")
@httpError(409)
structure Conflict {
    @required
    code: Integer = 409
    @required
    message: String = "Conflict."
}

@error("server")
@httpError(500)
structure InternalServerError {
    @required
    code: Integer = 500
    @required
    message: String = "Internal server error."
}

@error("server")
@httpError(503)
structure ServiceUnavailable {
    @required
    code: Integer = 503
    @required
    message: String = "The server is currently unavailable."
}

@error("client")
@httpError(404)
structure NotFound {
    @required
    code: Integer = 404
    @required
    message: String = "Not found."
}
