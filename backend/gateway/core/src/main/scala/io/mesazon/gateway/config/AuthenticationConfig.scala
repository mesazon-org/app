package io.mesazon.gateway.config

import zio.*

case class AuthenticationConfig(
    signInAttemptsMax: Int,
    signInAttemptsBlockDuration: Duration,
)

object AuthenticationConfig {
  val live = deriveConfigLayer[AuthenticationConfig]("authentication")
}
