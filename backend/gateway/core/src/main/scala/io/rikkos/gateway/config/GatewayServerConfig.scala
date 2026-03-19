package io.rikkos.gateway.config

import com.comcast.ip4s.*
import io.rikkos.gateway.config.GatewayServerConfig.ServerConfig
import zio.config.*
import zio.config.magnolia.*

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
