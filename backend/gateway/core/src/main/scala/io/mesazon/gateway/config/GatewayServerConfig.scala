package io.mesazon.gateway.config

import com.comcast.ip4s.*
import zio.config.*
import zio.config.magnolia.*

import GatewayServerConfig.ServerConfig

case class GatewayServerConfig(
    internal: ServerConfig,
    external: ServerConfig,
    health: ServerConfig,
    docs: ServerConfig,
)

object GatewayServerConfig {
  case class ServerConfig(host: Host, port: Port)

  val live = deriveConfigLayer[GatewayServerConfig]("server")
}
