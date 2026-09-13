package io.mesazon.gateway.clients

import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.mesazon.domain.gateway.{ServiceError, SupportedMediaType}
import io.mesazon.gateway.config.AIClientConfig
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.utils.FileByteStreamScanned
import sttp.ai.openai.OpenAI
import sttp.ai.openai.OpenAIExceptions.OpenAIException
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel, ResponseFormat}
import sttp.ai.openai.requests.completions.chat.message.*
import sttp.client4.{Backend, ResponseException, SttpClientException}
import sttp.model.StatusCode
import zio.*

import java.util.Base64
import scala.jdk.DurationConverters.JavaDurationOps

trait AIClient {
  def extractFromImage[A](
      imageByteStream: FileByteStreamScanned,
      supportedMediaType: SupportedMediaType,
      instructions: String,
  )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]
}

object AIClient {

  private final class AIClientImpl(
      openAI: OpenAI,
      backend: Backend[Task],
      aiClientConfig: AIClientConfig,
  ) extends AIClient {

    private def isRetryableSendError(error: Throwable): Boolean = error match {
      case _: SttpClientException.ResponseHandlingException[?] => false
      case _: SttpClientException.ConnectException             => true
      case _: SttpClientException.TimeoutException             => true
      case _: SttpClientException.ReadException                => true
      case openAIException: OpenAIException                    =>
        isRetryableSendError(openAIException.cause)
      case unexpectedStatusCode: ResponseException.UnexpectedStatusCode[?] =>
        unexpectedStatusCode.response.code match {
          case StatusCode.RequestTimeout | StatusCode.Conflict | StatusCode.TooManyRequests => true
          case statusCode if statusCode.code >= 500 && statusCode.code < 600                => true
          case _                                                                            => false
        }
      case _ => false
    }

    private def responseFormat[A](using schema: OpenAIJsonSchema[A]): ResponseFormat.JsonSchema =
      ResponseFormat.JsonSchema(
        name = "ai_client_response",
        strict = Some(true),
        schema = Some(schema.schema),
        description = None,
      )

    override def extractFromImage[A](
        imageByteStream: FileByteStreamScanned,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      for {
        imageBytes <- imageByteStream.value.runCollect
          .map(_.toArray)
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Failed to read image for AI extraction", Some(error))
          )
        imageBase64 = Base64.getEncoder.encodeToString(imageBytes)
        response <- openAI
          .createChatCompletion(
            ChatBody(
              model = ChatCompletionModel.GPT56Sol,
              messages = Seq(
                Message.System(instructions),
                Message.User(
                  Content.ArrayContent(
                    Seq(
                      Content.ContentPart.ImageUrl(
                        Content.ImageUrlDetails(url = s"data:${supportedMediaType.mime};base64,$imageBase64")
                      )
                    )
                  )
                ),
              ),
              responseFormat = Some(responseFormat),
            )
          )
          .readTimeout(aiClientConfig.requestTimeout.toScala)
          .send(backend)
          .map(_.body)
          .absolve
          .retry(
            Schedule.recurWhile[Throwable](isRetryableSendError) &&
              Schedule.recurs(aiClientConfig.sendMaxRetries) &&
              Schedule.exponential(aiClientConfig.sendRetryDelay)
          )
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Unable to send message to AI", Some(error))
          )
        result <- ZIO
          .attempt(readFromString[A](response.choices.head.message.content))
          .mapError(error =>
            ServiceError.InternalServerError
              .UnexpectedError(s"Failed to parse AI response ${response.choices.mkString("\n")}", Some(error))
          )
      } yield result
  }

  val live = ZLayer {
    for {
      aiClientConfig <- ZIO.service[AIClientConfig]
      backend        <- ZIO.service[Backend[Task]]
    } yield observed(
      new AIClientImpl(new OpenAI(aiClientConfig.apiKey, aiClientConfig.baseUri), backend, aiClientConfig)
    )
  }

  private def observed(client: AIClient): AIClient = client
}
