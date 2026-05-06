$version: "2.0"

namespace io.mesazon.gateway.smithy

structure ForgotPasswordPostRequest {
    @required
    email: String
}

structure ForgotPasswordPostResponse {
    @required
    otpID: String
    @required
    otpExpiresInSeconds: Long
}

structure ForgotPasswordVerifyPostRequest {
    @required
    otpID: String
    @required
    @sensitive
    otp: String
}

structure ForgotPasswordVerifyPostResponse {
    @required
    @sensitive
    resetPasswordToken: String
}

structure ResetPasswordPostRequest {
    @required
    @sensitive
    resetPasswordToken: String
    @required
    @sensitive
    password: String
}
