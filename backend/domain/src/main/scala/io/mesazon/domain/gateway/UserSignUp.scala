package io.mesazon.domain.gateway

case class SignUpEmailPostRequest(
    email: Email
)

case class SignUpVerifyEmailPostRequest(
    otpID: OtpID,
    otp: Otp,
)
