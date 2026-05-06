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

structure ForgotPasswordVerifyOTPPostRequest {
    @required
    otpID: String
    @required
    otp: String
}

structure ForgotPasswordVerifyOTPPostResponse {
    @required
    resetPasswordToken: String
}

structure ResetPasswordPostRequest {
    @required
    resetPasswordToken: String
    @required
    password: String
}
