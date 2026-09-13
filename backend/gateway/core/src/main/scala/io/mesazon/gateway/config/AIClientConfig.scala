package io.mesazon.gateway.config

import sttp.model.Uri
import zio.*

final case class AIClientConfig(
    scheme: String,
    host: String,
    port: Int,
    apiKey: String,
    requestTimeout: Duration,
    sendMaxRetries: Int,
    sendRetryDelay: Duration,
) {
  val baseUri: Uri = Uri.unsafeApply(scheme, host, port).addPath("v1")
}

object AIClientConfig {

  val live = deriveConfigLayer[AIClientConfig]("ai-client")
}
