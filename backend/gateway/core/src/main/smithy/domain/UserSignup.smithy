$version: "2.0"

namespace io.mesazon.gateway.smithy

structure SignUpEmailRequest {
    @required
    email: String
}

structure SignUpEmailResponse {
    @required
    otpID: String
    @required
    otpExpiresInSeconds: Long
}

structure SignUpVerifyEmailRequest {
    @required
    otpID: String
    @required
    otp: String
}

structure SignUpVerifyEmailResponse {
    @required
    accessTokenExpiresInSeconds: Long
    @required
    onboardStage: OnboardStage
    @required
    refreshToken: String
    @required
    accessToken: String
}
