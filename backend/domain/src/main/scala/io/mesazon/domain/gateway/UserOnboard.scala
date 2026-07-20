package io.mesazon.domain.gateway

case class OnboardPasswordPostRequest(
    password: Password
)

case class OnboardDetailsPostRequest(
    fullName: FullName,
    phoneNumber: PhoneNumber,
)

case class OnboardVerifyPhoneNumberPostRequest(
    otpID: OtpID,
    otp: Otp,
)
