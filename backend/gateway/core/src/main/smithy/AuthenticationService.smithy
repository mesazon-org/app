$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service AuthenticationService {
    version: "1.0.0",
    operations: [SignUpEmail]
}

@http(method: "POST", uri: "/signup/email", code: 204)
operation SignUpEmail {
    input := {
        @required
        @httpPayload
        request: SignUpEmailRequest
    }
    errors: [BadRequest, InternalServerError]
}