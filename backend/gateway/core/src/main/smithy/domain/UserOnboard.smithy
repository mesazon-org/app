$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

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
    otpID: UUID
    @required
    otpExpiresInSeconds: Long
}

structure OnboardVerifyPhoneNumberPostRequest {
    @required
    otpID: UUID
    @required
    otp: String
}

structure OnboardVerifyPhoneNumberPostResponse {
    @required
    onboardStage: OnboardStage
}

structure OnboardVerifyPhoneNumberGetResponse {
    @required
    otpID: UUID
    @required
    otpExpiresInSeconds: Long
}