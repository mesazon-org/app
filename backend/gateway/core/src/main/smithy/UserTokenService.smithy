$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service UserTokenService {
    version: "1.0.0",
    operations: [TokenRefreshPost]
}

/// **Required Onboard Stage:** [`EMAIL_VERIFIED`, `PASSWORD_PROVIDED`, `PHONE_VERIFICATION`, `PHONE_VERIFIED`]
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
