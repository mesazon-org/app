$version: "2.0"

namespace io.mesazon.gateway.smithy

enum OnboardStage {
    EMAIL_VERIFICATION
    EMAIL_VERIFIED
    PASSWORD_PROVIDED
    PHONE_VERIFICATION
    PHONE_VERIFIED
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

/// Restricts the operation to users that are assigned to the organization
/// identified by the required `X-Organization-ID` header with one of the
/// given roles.
@trait(selector: "operation")
structure organizationRolesAllowed {
    @required
    roles: OrganizationUserRoles
}
