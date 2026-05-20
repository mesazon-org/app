$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure CreateOrganizationPostRequest {
    @required
    email: String
}

structure CreateOrganizationPostResponse {
    @required
    organizationID: UUID
}