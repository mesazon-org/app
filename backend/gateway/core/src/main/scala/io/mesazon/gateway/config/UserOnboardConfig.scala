package io.mesazon.gateway.config

import zio.Duration

case class UserOnboardConfig(
    sendWelcomeEmailMaxRetries: Int,
    sendWelcomeEmailRetryDelay: Duration,
)

object UserOnboardConfig {
  val live = deriveConfigLayer[UserOnboardConfig]("user-onboard")
}
