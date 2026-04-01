$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service AuthenticationService {
    version: "1.0.0",
    operations: [SignUpEmail]
}

@http(method: "POST", uri: "/signup/email", code: 200)
operation SignUpEmail {
    input := {
        @required
        @httpPayload
        request: SignUpEmailRequest
    }
    output: SignUpEmailResponse
    errors: [BadRequest, InternalServerError]
}