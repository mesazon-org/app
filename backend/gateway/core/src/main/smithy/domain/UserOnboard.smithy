$version: "2.0"

namespace io.mesazon.gateway.smithy

structure OnboardPasswordRequest {
    @required
    password: String
}

structure OnboardPasswordResponse{
    @required
    onboardStage: OnboardStage
}
