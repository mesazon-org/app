$version: "2"
namespace io.rikkos.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserManagementService {
    version: "1.0.0",
    operations: [OnboardUser, UpdateUser]
}

@http(method: "POST", uri: "/users/onboard", code: 204)
operation OnboardUser {
    input := {
        @required
        @httpPayload
        request: OnboardUserDetailsRequest
    }
    errors: [BadRequest, Unauthorized, Conflict, InternalServerError]
}

@http(method: "POST", uri: "/users/update", code: 204)
operation UpdateUser {
    input := {
        @required
        @httpPayload
        request: UpdateUserDetailsRequest
    }
    errors: [BadRequest, Unauthorized, InternalServerError]
}

structure OnboardUserDetailsRequest {
    @required
    firstName: String
    @required
    lastName: String
    @required
    phoneRegion: String
    @required
    phoneNationalNumber: String
    @required
    addressLine1: String
    addressLine2: String
    @required
    city: String
    @required
    postalCode: String
    @required
    company: String
}

structure UpdateUserDetailsRequest {
    firstName: String
    lastName: String
    countryCode: String
    phoneNumber: String
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    company: String
}
