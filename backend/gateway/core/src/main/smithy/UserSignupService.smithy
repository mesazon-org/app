$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service UserSignupService {
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
    errors: [ValidationError, BadRequest, InternalServerError]
}

@auth([])
@http(method: "POST", uri: "/verify/email", code: 200)
operation VerifyEmail {
    input := {
        @required
        @httpPayload
        request: VerifyEmailRequest
    }
    output: VerifyEmailResponse
    errors: [ValidationError, BadRequest, InternalServerError]
}
