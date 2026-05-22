$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

/// **Required Onboard Stage:** [`PASSWORD_PROVIDED`, `PHONE_VERIFICATION`, `PHONE_VERIFIED`]
@simpleRestJson
service UserForgotPasswordService {
    version: "1.0.0",
    operations: [ForgotPasswordPost, ForgotPasswordVerifyOTPPost, ForgotPasswordResetPost]
}

/// **Required Onboard Stage:** [`PASSWORD_PROVIDED`, `PHONE_VERIFICATION`, `PHONE_VERIFIED`]
@http(method: "POST", uri: "/forgot/password", code: 200)
operation ForgotPasswordPost {
    input := {
        @required
        @httpPayload
        request: ForgotPasswordPostRequest
    }
    output: ForgotPasswordPostResponse
    errors: [ValidationError, Unauthorized, InternalServerError]
}

/// **Required Onboard Stage:** [`PASSWORD_PROVIDED`, `PHONE_VERIFICATION`, `PHONE_VERIFIED`]
@http(method: "POST", uri: "/forgot/password/verify-otp", code: 200)
operation ForgotPasswordVerifyOTPPost {
    input := {
        @required
        @httpPayload
        request: ForgotPasswordVerifyOTPPostRequest
    }
    output: ForgotPasswordVerifyOTPPostResponse
    errors: [ValidationError, BadRequest, Unauthorized, InternalServerError]
}

/// **Required Onboard Stage:** [`PASSWORD_PROVIDED`, `PHONE_VERIFICATION`, `PHONE_VERIFIED`]
@http(method: "POST", uri: "/forgot/password/reset", code: 204)
operation ForgotPasswordResetPost {
    input := {
        @required
        @httpPayload
        request: ForgotPasswordResetPostRequest
    }
    errors: [ValidationError, Unauthorized, InternalServerError]
}