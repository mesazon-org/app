$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure CreateOrganizationPostRequest {
    @required
    name: String
    @required
    slug: String
    @required
    email: String
    @required
    phoneNumber: PhoneNumberRequest
    @required
    addressLine1: String
    addressLine2: String
    @required
    city: String
    @required
    postalCode: String
    @required
    country: String
}

structure CreateOrganizationPostResponse {
    @required
    organizationID: UUID
}