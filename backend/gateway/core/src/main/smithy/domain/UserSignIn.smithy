$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

structure SignInPostResponse {
    @required
    accessTokenExpiresInSeconds: Long
    @required
    onboardStage: OnboardStage
    @required
    refreshToken: String
    @required
    accessToken: String
}
