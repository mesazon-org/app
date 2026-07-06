package io.mesazon.gateway.config

import zio.*

case class UserForgotPasswordConfig(
    isDev: Boolean,
    otpExpiresAtOffset: Duration,
    otpResendCooldown: Duration,
    otpResetAttemptsMaxRetries: Int,
    sendForgotPasswordEmailMaxRetries: Int,
    sendForgotPasswordEmailRetryDelay: Duration,
    otpVerifyAttemptsMaxRetries: Int,
    sendPasswordChangeConfirmationEmailMaxRetries: Int,
    sendPasswordChangeConfirmationEmailRetryDelay: Duration,
)

object UserForgotPasswordConfig {
  val live = deriveConfigLayer[UserForgotPasswordConfig]("user-forgot-password")
}
