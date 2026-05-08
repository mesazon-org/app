package io.mesazon.domain.gateway

case class ForgotPasswordReset(
    resetPasswordToken: ResetPasswordToken,
    password: Password,
)
