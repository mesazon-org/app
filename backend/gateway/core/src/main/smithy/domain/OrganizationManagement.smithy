$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure OrganizationEmailRequest {
    @required
    email: String
    @required
    isDefault: Boolean
}

list OrganizationEmailRequests {
    member: OrganizationEmailRequest
}

structure OrganizationPhoneNumberRequest {
    @required
    phoneNumber: PhoneNumberRequest
    @required
    isDefault: Boolean
}

list OrganizationPhoneNumberRequests {
    member: OrganizationPhoneNumberRequest
}

structure CreateOrganizationPostRequest {
    @required
    name: String
    @required
    slug: String
    tagline: String
    @required
    emails: OrganizationEmailRequests
    @required
    phoneNumbers: OrganizationPhoneNumberRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
    companyRegistrationNumber: String
    taxID: String
}

structure CreateOrganizationPostResponse {
    @required
    organizationID: UUID
}