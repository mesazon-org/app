package io.mesazon.gateway.config

case class OpenAIClientConfig(
    apiKey: String
)

object OpenAIClientConfig {

  val live = deriveConfigLayer[OpenAIClientConfig]("open-ai-client")
}
