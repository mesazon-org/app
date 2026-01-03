package io.mesazon.waha.config

import sttp.model.Uri

import scala.concurrent.duration.Duration

case class WahaConfig(
    scheme: String,
    host: String,
    port: Int,
    apiKey: String,
    wordsPerMinute: Int,
    humanDelayMin: Duration,
    humanDelayMax: Duration,
) {
  val baseUri = Uri.unsafeApply(scheme, host, port)
}
