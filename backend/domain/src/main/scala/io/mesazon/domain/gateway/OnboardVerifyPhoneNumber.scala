package io.mesazon.domain.gateway

case class OnboardVerifyPhoneNumber(
    otpID: OtpID,
    otp: Otp,
)
