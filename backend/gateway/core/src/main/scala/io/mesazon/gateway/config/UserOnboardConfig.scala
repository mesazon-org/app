package io.mesazon.gateway.config

import zio.Duration

case class UserOnboardConfig(
    sendEmailVerificationEmailMaxRetries: Int,
    sendEmailVerificationEmailRetryDelay: Duration,
)

object UserOnboardConfig {
  val live = deriveConfigLayer[UserOnboardConfig]("user-onboard")
}
