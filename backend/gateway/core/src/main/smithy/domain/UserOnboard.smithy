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

structure OnboardDetailsRequest {
    @required
    fullName: String
    @required
    phoneNumber: PhoneNumberRequest
}

structure OnboardDetailsResponse {
    @required
    onboardStage: OnboardStage
}

structure PhoneNumberRequest {
    @required
    nationalNumber: String
    @required
    countryCode: String
}