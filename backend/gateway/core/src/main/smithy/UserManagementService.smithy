$version: "2"
namespace io.rikkos.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserManagementService {
    version: "1.0.0",
    operations: [OnboardUser]
}

@http(method: "POST", uri: "/users/onboard", code: 204)
operation OnboardUser {
    input := {
        @required
        @httpPayload
        request: OnboardUserDetailsRequest
    }
    errors: [BadRequest, Unauthorized, InternalServerError]
}

structure OnboardUserDetailsRequest {
    @required
    firstName: String
    @required
    lastName: String
    @required
    organization: String
}
