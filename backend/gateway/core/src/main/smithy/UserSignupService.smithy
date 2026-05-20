$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service UserSignUpService {
    version: "1.0.0",
    operations: [SignUpEmailPost, SignUpVerifyEmailPost]
}

/// **Required Onboard Stage:** [``, `EMAIL_VERIFICATION`, `EMAIL_VERIFIED`]
@http(method: "POST", uri: "/signup/email", code: 200)
operation SignUpEmailPost {
    input := {
        @required
        @httpPayload
        request: SignUpEmailPostRequest
    }
    output: SignUpEmailPostResponse
    errors: [ValidationError, InternalServerError]
}

/// **Required Onboard Stage:** [`EMAIL_VERIFICATION`]
@http(method: "POST", uri: "/signup/verify/email", code: 200)
operation SignUpVerifyEmailPost {
    input := {
        @required
        @httpPayload
        request: SignUpVerifyEmailPostRequest
    }
    output: SignUpVerifyEmailPostResponse
    errors: [ValidationError, BadRequest, Unauthorized, InternalServerError]
}
