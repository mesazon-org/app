package io.mesazon.gateway.config

import zio.*

final case class HttpClientConfig(connectionTimeout: Duration)

object HttpClientConfig {

  val live = deriveConfigLayer[HttpClientConfig]("http-client")
}
