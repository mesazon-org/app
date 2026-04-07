$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service AuthenticationService {
    version: "1.0.0",
    operations: [SignUpEmail, VerifyEmail]
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

@http(method: "POST", uri: "/verify/email", code: 200)
operation VerifyEmail {
    input := {
        @required
        @httpPayload
        request: VerifyEmailRequest
    }
    output : VerifyEmailResponse
    errors: [BadRequest, Unauthorized, InternalServerError]
}