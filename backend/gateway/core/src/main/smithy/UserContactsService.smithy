$version: "2"
namespace io.rikkos.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserContactsService {
    version: "1.0.0",
    operations: [UpsertContacts]
}

@http(method: "POST", uri: "/contacts/upsert", code: 204)
operation UpsertContacts {
    input := {
        @required
        @httpPayload
        request: UpsertUserContactRequestList
    }
    errors: [BadRequest, Unauthorized, InternalServerError]
}

@uniqueItems
list UpsertUserContactRequestList {
    member: UpsertUserContactRequest
}

structure UpsertUserContactRequest {
    userContactID: String
    @required
    displayName: String
    @required
    firstName: String
    lastName: String
    email: String
    @required
    phoneRegion: String
    @required
    phoneNationalNumber: String
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    company: String
}
