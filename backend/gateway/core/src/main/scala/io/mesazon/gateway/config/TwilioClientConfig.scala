package io.mesazon.gateway.config

import sttp.model.Uri

final case class TwilioClientConfig(
    scheme: String,
    host: String,
    port: Int,
    accountSid: String,
    authToken: String,
    companyName: String,
) {
  val baseUri: Uri = Uri(scheme, host, port)
}

object TwilioClientConfig {

  val live = deriveConfigLayer[TwilioClientConfig]("twilio-client")
}
