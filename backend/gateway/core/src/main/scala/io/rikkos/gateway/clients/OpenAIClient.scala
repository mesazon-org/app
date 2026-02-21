package io.rikkos.gateway.clients

import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.rikkos.domain.gateway.ServiceError
import io.rikkos.gateway.config.OpenAIConfig
import sttp.ai.openai.OpenAI
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel, ResponseFormat}
import sttp.ai.openai.requests.completions.chat.message.*
import sttp.ai.openai.requests.completions.chat.message.Message.SystemMessage
import sttp.client4.Backend
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema
import zio.*

trait OpenAIClient {
  def sendMessage[A](messages: Seq[Message])(using Schema[A], JsonValueCodec[A]): IO[ServiceError, A]
}

object OpenAIClient {

  private final class OpenAIClientImpl(
      openAI: OpenAI,
      backend: Backend[Task],
  ) extends OpenAIClient {

    private def responseFormat[A](using schema: Schema[A]): ResponseFormat.JsonSchema =
      ResponseFormat.JsonSchema(
        name = "whatsapp_response",
        strict = Some(true),
        schema = Some(
          TapirSchemaToJsonSchema(
            schema,
            markOptionsAsNullable = true,
          )
        ),
        description = None,
      )

    override def sendMessage[A](
        messages: Seq[Message]
    )(using Schema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      openAI
        .createChatCompletion(
          ChatBody(
            model = ChatCompletionModel.GPT5,
            messages = SystemMessage(
              """You are a whatsapp agent replying back to user with concise messages.
                | Max message length should be 500 characters.
                | Do not include any emojis or special characters in the response.
                | Do not include new lines extra lines in the response.
                | Language should be selected based on user messages.""".stripMargin
            ) +: messages,
            responseFormat = Some(responseFormat),
          )
        )
        .send(backend)
        .map(_.body)
        .absolve
        .mapError(error =>
          ServiceError.InternalServerError.UnexpectedError("Unable to send message to OpenAI", Some(error))
        )
        .tap(response => ZIO.logDebug(s"Received response from OpenAI: ${response.choices}"))
        .flatMap(r =>
          ZIO
            .attempt(
              readFromString[A](r.choices.head.message.content)
            )
            .mapError(error =>
              ServiceError.InternalServerError.UnexpectedError("Failed to parse OpenAI response", Some(error))
            )
        )
  }

  private def observed(client: OpenAIClient): OpenAIClient = client

  val openAILive = ZLayer {
    for {
      openAIConfig <- ZIO.service[OpenAIConfig]
    } yield new OpenAI(openAIConfig.apiKey)
  }

  val live = ZLayer.derive[OpenAIClientImpl] >>> ZLayer.fromFunction(observed)
}
