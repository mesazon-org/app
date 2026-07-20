$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure OrganizationEmailEntryRequest {
    @required
    email: String
    @required
    isDefault: Boolean
}

list OrganizationEmailEntryRequests {
    member: OrganizationEmailEntryRequest
}

structure OrganizationPhoneNumberEntryRequest {
    @required
    phoneNumber: PhoneNumberRequest
    @required
    isDefault: Boolean
}

list OrganizationPhoneNumberEntryRequests {
    member: OrganizationPhoneNumberEntryRequest
}

structure CreateOrganizationPostRequest {
    @required
    name: String
    @required
    slug: String
    tagline: String
    @required
    emails: OrganizationEmailEntryRequests
    @required
    phoneNumbers: OrganizationPhoneNumberEntryRequests
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