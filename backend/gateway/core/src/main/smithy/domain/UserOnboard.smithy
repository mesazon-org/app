$version: "2.0"

namespace io.mesazon.gateway.smithy

structure OnboardPasswordPostRequest {
    @required
    password: String
}

structure OnboardPasswordPostResponse{
    @required
    onboardStage: OnboardStage
}

structure OnboardDetailsPostRequest {
    @required
    fullName: String
    @required
    phoneNumber: PhoneNumberRequest
}

structure OnboardDetailsPostResponse {
    @required
    onboardStage: OnboardStage
    @required
    otpID: String
    @required
    otpExpiresInSeconds: Long
}

structure OnboardVerifyPhoneNumberPostRequest {
    @required
    otpID: String
    @required
    otp: String
}

structure OnboardVerifyPhoneNumberPostResponse {
    @required
    onboardStage: OnboardStage
}

structure OnboardVerifyPhoneNumberGetResponse {
    @required
    otpID: String
    @required
    otp: String
}