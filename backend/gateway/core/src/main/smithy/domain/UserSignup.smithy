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

structure VerifyEmailRequest {
    @required
    otpID: String
    @required
    otp: String
}

structure VerifyEmailResponse {
    @required
    expiresInSeconds: Long
    @required
    onboardStage: OnboardStage
    @required
    refreshToken: String
    @required
    accessToken: String
}
