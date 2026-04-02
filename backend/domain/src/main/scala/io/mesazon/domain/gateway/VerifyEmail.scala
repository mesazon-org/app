package io.mesazon.domain.gateway

case class VerifyEmail(
    otpID: OtpID,
    otp: Otp,
)
