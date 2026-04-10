package io.mesazon.gateway.config

import zio.*

case class UserSignupConfig(
    otpEmailVerificationExpiresAtOffset: Duration,
    otpEmailVerificationResendCooldown: Duration,
    sendEmailVerificationEmailMaxRetries: Int,
    sendEmailVerificationEmailRetryDelay: Duration,
)

object UserSignupConfig {

  val live = deriveConfigLayer[UserSignupConfig]("user-signup")
}
