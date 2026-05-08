package io.mesazon.domain.gateway

case class ForgotPasswordVerifyOTP(
    otpID: OtpID,
    otp: Otp,
)
