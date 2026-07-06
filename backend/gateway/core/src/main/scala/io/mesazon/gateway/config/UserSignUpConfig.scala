package io.mesazon.gateway.config

import zio.*

case class UserSignUpConfig(
    isDev: Boolean,
    otpEmailVerificationExpiresAtOffset: Duration,
    otpEmailVerificationResendCooldown: Duration,
    sendEmailVerificationEmailMaxRetries: Int,
    sendEmailVerificationEmailRetryDelay: Duration,
)

object UserSignUpConfig {

  val live = deriveConfigLayer[UserSignUpConfig]("user-sign-up")
}
