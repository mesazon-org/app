$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBasicAuth
service UserSignInService {
    version: "1.0.0",
    operations: [SignInPost]
}

/// **Required Onboard Stages:** [`PASSWORD_PROVIDED`, `PHONE_VERIFICATION`, `PHONE_VERIFIED`]
@http(method: "POST", uri: "/signin", code: 200)
operation SignInPost {
    output: SignInPostResponse
    errors: [Unauthorized, Forbidden, BadRequest, ValidationError, InternalServerError]
}
