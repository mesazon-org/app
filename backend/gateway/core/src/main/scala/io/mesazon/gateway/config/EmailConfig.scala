package io.mesazon.gateway.config

import sttp.model.*

case class EmailConfig(
    host: String,
    port: Int,
    senderEmail: String,
    senderPassword: String,
    redirectUri: Uri,
    enableTls: Boolean,
)

object EmailConfig {

  val live = deriveConfigLayer[EmailConfig]("email")
}
