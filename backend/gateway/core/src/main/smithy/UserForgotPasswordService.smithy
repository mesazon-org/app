$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service UserForgotPasswordService {
    version: "1.0.0",
    operations: [ForgotPasswordPost, ForgotPasswordVerifyOTPPost, ForgotPasswordResetPost]
}

@http(method: "POST", uri: "/forgot/password", code: 200)
operation ForgotPasswordPost {
    input := {
        @required
        @httpPayload
        request: ForgotPasswordPostRequest
    }
    output: ForgotPasswordPostResponse
    errors: [ValidationError, InternalServerError]
}

@http(method: "POST", uri: "/forgot/password/verify-otp", code: 200)
operation ForgotPasswordVerifyOTPPost {
    input := {
        @required
        @httpPayload
        request: ForgotPasswordVerifyOTPPostRequest
    }
    output: ForgotPasswordVerifyOTPPostResponse
    errors: [ValidationError, TooManyRequests, InternalServerError]
}

@http(method: "POST", uri: "/forgot/password/reset", code: 200)
operation ForgotPasswordResetPost {
    input := {
        @required
        @httpPayload
        request: ResetPasswordPostRequest
    }
    errors: [ValidationError, InternalServerError]
}