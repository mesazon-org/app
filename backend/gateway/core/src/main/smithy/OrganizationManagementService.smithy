$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

/// **Required Onboard Stage:** COMPLETED
@simpleRestJson
@completedOnboardStage
@httpBearerAuth
service OrganizationManagementService {
    version: "1.0.0",
    operations: [CreateOrganizationPost]
}

@http(method: "POST", uri: "/create/organization", code: 200)
operation CreateOrganizationPost {
    input := {
        @required
        @httpPayload
        request: CreateOrganizationPostRequest
    }
    output: CreateOrganizationPostResponse
    errors: [ValidationError, Unauthorized, Forbidden, InternalServerError]
}
