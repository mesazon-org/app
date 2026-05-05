$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service UserOnboardService {
    version: "1.0.0",
    operations: [OnboardPasswordPost, OnboardDetailsPost, OnboardVerifyPhoneNumberPost, OnboardVerifyPhoneNumberGet]
}

@http(method: "POST", uri: "/onboard/password", code: 200)
operation OnboardPasswordPost {
    input := {
        @required
        @httpPayload
        request: OnboardPasswordPostRequest
    }
    output: OnboardPasswordPostResponse
    errors: [Unauthorized, ValidationError, InternalServerError]
}

@http(method: "POST", uri: "/onboard/details", code: 200)
operation OnboardDetailsPost {
    input := {
        @required
        @httpPayload
        request: OnboardDetailsPostRequest
    }
    output: OnboardDetailsPostResponse
    errors: [Unauthorized, ValidationError, InternalServerError]
}


@http(method: "POST", uri: "/onboard/verify/phone-number", code: 200)
operation OnboardVerifyPhoneNumberPost {
    input := {
        @required
        @httpPayload
        request: OnboardVerifyPhoneNumberPostRequest
    }
    output: OnboardVerifyPhoneNumberPostResponse
    errors: [Unauthorized, ValidationError, InternalServerError]
}

@http(method: "GET", uri: "/onboard/verify/phone-number", code: 200)
operation OnboardVerifyPhoneNumberGet {
    output: OnboardVerifyPhoneNumberGetResponse
    errors: [Unauthorized, InternalServerError]
}
