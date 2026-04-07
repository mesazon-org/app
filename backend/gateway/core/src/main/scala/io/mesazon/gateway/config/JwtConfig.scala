package io.mesazon.gateway.config

import zio.*

import javax.crypto.SecretKey

case class JwtConfig(
    secretKey: SecretKey,
    issuer: String,
    accessTokenExpiresAtOffset: Duration,
    refreshTokenExpiresAtOffset: Duration,
)

object JwtConfig {

  val live = deriveConfigLayer[JwtConfig]("jwt")
}
