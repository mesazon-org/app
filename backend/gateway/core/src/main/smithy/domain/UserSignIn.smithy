$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

structure SignInPostResponse {
    @required
    accessTokenExpiresInSeconds: Long
    @required
    onboardStage: OnboardStage
    @required
    @sensitive
    refreshToken: String
    @required
    @sensitive
    accessToken: String
}
