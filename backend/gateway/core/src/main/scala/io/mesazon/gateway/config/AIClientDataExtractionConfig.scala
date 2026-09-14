package io.mesazon.gateway.config

import sttp.model.Uri
import zio.*

final case class AIClientDataExtractionConfig(
    scheme: String,
    host: String,
    port: Int,
    apiKey: String,
    requestTimeout: Duration,
    sendMaxRetries: Int,
    sendRetryDelay: Duration,
    csvBatchMaxDataRows: Int,
    csvBatchParallelism: Int,
) {
  val baseUri: Uri = Uri.unsafeApply(scheme, host, port).addPath("v1")
}

object AIClientDataExtractionConfig {

  val live = deriveConfigLayer[AIClientDataExtractionConfig]("ai-client-data-extraction")
}
