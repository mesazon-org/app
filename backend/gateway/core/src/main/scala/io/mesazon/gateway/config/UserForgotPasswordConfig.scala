package io.mesazon.gateway.config

import zio.*

case class UserForgotPasswordConfig(
    otpExpiresAtOffset: Duration,
    otpResendCooldown: Duration,
    otpResetAttemptsMaxRetries: Int,
    sendForgotPasswordEmailMaxRetries: Int,
    sendForgotPasswordEmailRetryDelay: Duration,
)

object UserForgotPasswordConfig {
  val live = deriveConfigLayer[UserForgotPasswordConfig]("user-forgot-password")
}
