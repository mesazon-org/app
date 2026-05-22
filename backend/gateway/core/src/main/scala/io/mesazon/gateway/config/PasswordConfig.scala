package io.mesazon.gateway.config

case class PasswordConfig(
    saltLength: Int,
    hashLength: Int,
    parallelism: Int,
    memoryKB: Int,
    iterations: Int,
)

object PasswordConfig {

  val live = deriveConfigLayer[PasswordConfig]("password")
}
