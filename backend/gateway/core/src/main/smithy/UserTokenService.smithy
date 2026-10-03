$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service UserTokenService {
    version: "1.0.0",
    operations: [TokenRefreshPost]
}

/// **Required Onboard Stage:** [`EmailVerified`, `PasswordProvided`, `PhoneVerification`, `PhoneVerified`]
@http(method: "POST", uri: "/token/refresh", code: 200)
operation TokenRefreshPost {
    input := {
        @required
        @httpPayload
        request: TokenRefreshPostRequest
    }
    output: TokenRefreshPostResponse
    errors: [ValidationError, Unauthorized, InternalServerError]
}
