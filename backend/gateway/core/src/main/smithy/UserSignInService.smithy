$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBasicAuth
service UserSignInService {
    version: "1.0.0",
    operations: [SignInPost]
}

@http(method: "POST", uri: "/signin", code: 200)
operation SignInPost {
    output: SignInPostResponse
    errors: [Unauthorized, BadRequest, ValidationError, InternalServerError]
}
