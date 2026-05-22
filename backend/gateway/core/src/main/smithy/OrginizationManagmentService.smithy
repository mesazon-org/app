$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

/// # Global Requirements
/// **Required Onboard Stage:** **COMPLETED**
///
/// All endpoints in this service require the user to have finished
/// the onboarding flow (Phone & Email Verified).
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
    errors: [Unauthorized, ValidationError, InternalServerError]
}