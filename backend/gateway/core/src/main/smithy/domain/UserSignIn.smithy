$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson
use alloy#UUID

structure SignInPostResponse {
    @required
    accessTokenExpiresInSeconds: Long
    @required
    onboardStage: OnboardStage
    @required
    refreshToken: String
    @required
    accessToken: String
    @required
    organizations: SignInOrganizations
}

structure SignInOrganization {
    @required
    organizationID: UUID
    @required
    name: String
    @required
    slug: String
    @required
    role: OrganizationUserRole
    logoUrl: String
}

list SignInOrganizations {
    member: SignInOrganization
}
