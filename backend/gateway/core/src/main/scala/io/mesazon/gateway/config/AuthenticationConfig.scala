package io.mesazon.gateway.config

import zio.*

case class AuthenticationConfig(
    otpExpiration: Duration,
    otpResendCooldown: Duration,
    sendEmailVerificationEmailMaxRetries: Int,
    sendEmailVerificationEmailRetryDelay: Duration,
)

object AuthenticationConfig {

  val live = deriveConfigLayer[AuthenticationConfig]("authentication")
}
