package io.mesazon.gateway.config

import sttp.model.Uri

case class EmailConfig(
    host: String,
    port: Int,
    senderEmail: String,
    senderPassword: String,
    redirectScheme: String,
    redirectHost: String,
    redirectPort: Int,
    enableTls: Boolean,
) {
  val redirectUri: Uri = Uri.unsafeApply(redirectScheme, redirectHost, redirectPort)
}

object EmailConfig {

  val live = deriveConfigLayer[EmailConfig]("email")
}
