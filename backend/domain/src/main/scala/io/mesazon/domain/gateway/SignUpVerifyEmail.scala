package io.mesazon.domain.gateway

case class SignUpVerifyEmail(
    otpID: OtpID,
    otp: Otp,
)
