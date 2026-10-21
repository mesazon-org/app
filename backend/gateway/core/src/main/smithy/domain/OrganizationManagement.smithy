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

list OrganizationAddressEntryRequests {
    member: AddressEntryRequest
}

structure CreateOrganizationPostRequest {
    @required
    name: String
    @required
    slug: String
    tagline: String
    @default([])
    emails: OrganizationEmailEntryRequests
    @default([])
    phoneNumbers: OrganizationPhoneNumberEntryRequests
    @default([])
    addresses: OrganizationAddressEntryRequests
    companyRegistrationNumber: String
    taxID: String
}

structure CreateOrganizationPostResponse {
    @required
    organizationID: UUID
}