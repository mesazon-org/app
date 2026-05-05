$version: "2.0"

namespace io.mesazon.gateway.smithy

structure SignUpEmailPostRequest {
    @required
    email: String
}

structure SignUpEmailPostResponse {
    @required
    otpID: String
    @required
    otpExpiresInSeconds: Long
}

structure SignUpVerifyEmailPostRequest {
    @required
    otpID: String
    @required
    otp: String
}

structure SignUpVerifyEmailPostResponse {
    @required
    accessTokenExpiresInSeconds: Long
    @required
    onboardStage: OnboardStage
    @required
    refreshToken: String
    @required
    accessToken: String
}
