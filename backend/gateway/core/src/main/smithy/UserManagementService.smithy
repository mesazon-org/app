$version: "2"
namespace io.rikkos.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserManagementService {
    version: "1.0.0",
    operations: [OnboardUser, EditUser]
}

@http(method: "POST", uri: "/users/onboard", code: 204)
operation OnboardUser {
    input := {
        @required
        @httpPayload
        request: OnboardUserDetailsRequest
    }
    errors: [BadRequest, Unauthorized, InternalServerError]
}

@http(method: "POST", uri: "/users/edit", code: 204)
operation EditUser {
    input := {
        @required
        @httpPayload
        request: EditUserDetailsRequest
    }
    errors: [BadRequest, Unauthorized, InternalServerError]
}

structure OnboardUserDetailsRequest {
    @required
    firstName: String
    @required
    lastName: String
    @required
    countryCode: String
    @required
    phoneNumber: String
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

structure EditUserDetailsRequest {
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
