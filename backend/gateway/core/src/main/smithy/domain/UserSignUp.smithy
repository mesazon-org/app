$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure SignUpEmailPostRequest {
    @required
    email: String
}

structure SignUpEmailPostResponse {
    @required
    otpID: UUID
    @required
    otpExpiresInSeconds: Long
}

structure SignUpVerifyEmailPostRequest {
    @required
    otpID: UUID
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
