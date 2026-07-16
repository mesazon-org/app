$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

enum OnboardStage {
    EMAIL_VERIFICATION
    EMAIL_VERIFIED
    PASSWORD_PROVIDED
    PHONE_VERIFICATION
    PHONE_VERIFIED
}

enum CustomerType {
    INDIVIDUAL
    BUSINESS
}

structure PhoneNumberRequest {
    @required
    phoneNationalNumber: String
    @required
    phoneCountryCode: String
}

@trait(selector: "service")
structure completedOnboardStage {}

enum OrganizationUserRole {
    OWNER
    ADMIN
    USER
}

list OrganizationUserRoles {
    member: OrganizationUserRole
}

@trait(selector: "operation")
structure organizationUserRolesAllowed {
    @required
    roles: OrganizationUserRoles
}

/// Mixin carrying the required `X-Organization-ID` header that scopes an
/// operation to a single organization. Every org-scoped operation input mixes
/// this in instead of redeclaring the header, so the header is defined once and
/// still renders as a required parameter on each operation in swagger.
@mixin
structure OrganizationScopedInput {
    @required
    @httpHeader("X-Organization-ID")
    organizationID: UUID
}
