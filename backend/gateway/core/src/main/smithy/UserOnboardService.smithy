$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserOnboardService {
    version: "1.0.0",
    operations: [OnboardPassword, OnboardDetails, OnboardVerifyPhoneNumber]
}

@http(method: "POST", uri: "/onboard/password", code: 200)
operation OnboardPassword {
    input := {
        @required
        @httpPayload
        request: OnboardPasswordRequest
    }
    output: OnboardPasswordResponse
    errors: [Unauthorized, ValidationError, InternalServerError]
}

@http(method: "POST", uri: "/onboard/details", code: 200)
operation OnboardDetails {
    input := {
        @required
        @httpPayload
        request: OnboardDetailsRequest
    }
    output: OnboardDetailsResponse
    errors: [Unauthorized, ValidationError, InternalServerError]
}


@http(method: "POST", uri: "/onboard/verify/phone-number", code: 200)
operation OnboardVerifyPhoneNumber {
    input := {
        @required
        @httpPayload
        request: OnboardVerifyPhoneNumberRequest
    }
    output: OnboardVerifyPhoneNumberResponse
    errors: [Unauthorized, ValidationError, InternalServerError]
}
