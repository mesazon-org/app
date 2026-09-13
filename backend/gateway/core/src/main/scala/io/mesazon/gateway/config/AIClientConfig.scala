package io.mesazon.gateway.config

import sttp.model.Uri

final case class AIClientConfig(
    scheme: String,
    host: String,
    port: Int,
    apiKey: String,
    requestTimeout: zio.Duration,
    sendMaxRetries: Int,
    sendRetryDelay: zio.Duration,
) {
  val baseUri: Uri = Uri.unsafeApply(scheme, host, port).addPath("v1")
}

object AIClientConfig {

  val live = deriveConfigLayer[AIClientConfig]("ai-client")
}
