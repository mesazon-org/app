$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBasicAuth
service UserSignInService {
    version: "1.0.0",
    operations: [SignIn]
}

@http(method: "POST", uri: "/signin", code: 200)
operation SignIn {
    output: SignInResponse
    errors: [Unauthorized, BadRequest, ValidationError, InternalServerError]
}
