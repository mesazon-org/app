package io.rikkos.gateway.config

case class OpenAIConfig(
    apiKey: String
)

object OpenAIConfig {

  val live = deriveConfigLayer[OpenAIConfig]("open-ai")
}
