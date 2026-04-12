$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserOnboardService {
    version: "1.0.0",
    operations: [OnboardPassword]
}

@http(method: "POST", uri: "/onboard/password", code: 200)
operation OnboardPassword {
    input := {
        @required
        @httpPayload
        request: OnboardPasswordRequest
    }
    output: OnboardResponse
    errors: [Unauthorized, BadRequest, InternalServerError]
}
