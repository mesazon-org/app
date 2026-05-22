$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure ForgotPasswordPostRequest {
    @required
    email: String
}

structure ForgotPasswordPostResponse {
    @required
    otpID: UUID
    @required
    otpExpiresInSeconds: Long
}

structure ForgotPasswordVerifyOTPPostRequest {
    @required
    otpID: UUID
    @required
    otp: String
}

structure ForgotPasswordVerifyOTPPostResponse {
    @required
    resetPasswordToken: String
    @required
    resetPasswordTokenExpiresInSeconds: Long
}

structure ForgotPasswordResetPostRequest {
    @required
    resetPasswordToken: String
    @required
    password: String
}
