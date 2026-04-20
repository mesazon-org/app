$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service UserSignUpService {
    version: "1.0.0",
    operations: [SignUpEmail, SignUpVerifyEmail]
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
@http(method: "POST", uri: "/signup/verify/email", code: 200)
operation SignUpVerifyEmail {
    input := {
        @required
        @httpPayload
        request: SignUpVerifyEmailRequest
    }
    output: SignUpVerifyEmailResponse
    errors: [ValidationError, BadRequest, Unauthorized, InternalServerError]
}
