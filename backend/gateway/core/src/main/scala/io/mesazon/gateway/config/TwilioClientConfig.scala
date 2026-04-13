package io.mesazon.gateway.config

final case class TwilioClientConfig(
    accountSid: String,
    authToken: String,
    phoneNumber: String,
)

object TwilioClientConfig {

  val live = deriveConfigLayer[TwilioClientConfig]("twilio-client")
}
