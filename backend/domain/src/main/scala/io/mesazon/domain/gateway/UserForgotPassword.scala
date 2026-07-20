package io.mesazon.domain.gateway

case class ForgotPasswordPostRequest(
    email: Email
)

case class ForgotPasswordVerifyOTPPostRequest(
    otpID: OtpID,
    otp: Otp,
)

case class ForgotPasswordResetPostRequest(
    resetPasswordToken: ResetPasswordToken,
    password: Password,
)
