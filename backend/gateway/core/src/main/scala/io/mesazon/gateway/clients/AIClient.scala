package io.mesazon.gateway.clients

import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.mesazon.domain.gateway.{ExtractCustomersResponse, ServiceError, SupportedMediaType}
import io.mesazon.gateway.config.AIClientConfig
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.extractCustomersResponseCodec
import io.mesazon.gateway.utils.*
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVPrinter}
import sttp.ai.openai.OpenAI
import sttp.ai.openai.OpenAIExceptions.OpenAIException
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel, ResponseFormat}
import sttp.ai.openai.requests.completions.chat.message.*
import sttp.client4.{Backend, ResponseException, SttpClientException}
import sttp.model.StatusCode
import zio.*

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.{Base64, Locale}
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.Using

trait AIClient {
  def extractFromImage[A](
      imageScannedPath: FileScannedPath,
      supportedMediaType: SupportedMediaType,
      instructions: String,
  )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

  def extractFromCsv(
      csvValidatedPath: CsvValidatedPath,
      instructions: String,
  ): IO[ServiceError, ExtractCustomersResponse]
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

    private def sendAndDecode[A](
        model: ChatCompletionModel,
        instructions: String,
        content: Content,
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      for {
        response <- openAI
          .createChatCompletion(
            ChatBody(
              model = model,
              messages = Seq(
                Message.System(instructions),
                Message.User(content),
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

    private def extractFromImageBytes[A](
        imageBytes: Array[Byte],
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] = {
      val imageBase64 = Base64.getEncoder.encodeToString(imageBytes)
      val imageMime   = supportedMediaType.mimes.head
      sendAndDecode[A](
        ChatCompletionModel.GPT56Sol,
        instructions,
        Content.ArrayContent(
          Seq(
            Content.ContentPart.ImageUrl(
              Content.ImageUrlDetails(url = s"data:$imageMime;base64,$imageBase64")
            )
          )
        ),
      )
    }

    private def csvBatches(csvValidatedPath: CsvValidatedPath): IO[ServiceError, NonEmptyChunk[String]] =
      ZIO.attemptBlocking {
        Using.resource(
          CSVParser.parse(
            Files.newBufferedReader(csvValidatedPath.value, StandardCharsets.UTF_8),
            CSVFormat.DEFAULT,
          )
        ) { csvParser =>
          val records  = csvParser.iterator().asScala.map(_.iterator().asScala.toList).toList
          val header   = records.headOption.getOrElse(List.empty)
          val dataRows = records
            .drop(1)
            .filterNot(_.forall(_.trim.isEmpty))
          val dataRowBatches = dataRows.grouped(aiClientConfig.csvBatchMaxDataRows).map(_.toList).toList match {
            case Nil     => List(List.empty[List[String]])
            case batches => batches
          }
          val batchTexts = dataRowBatches.map { batchRows =>
            val writer = new StringWriter()
            Using.resource(new CSVPrinter(writer, CSVFormat.DEFAULT)) { csvPrinter =>
              csvPrinter.printRecord(header*)
              batchRows.foreach(row => csvPrinter.printRecord(row*))
            }
            writer.toString
          }

          NonEmptyChunk(batchTexts.head, batchTexts.tail*)
        }
      }
        .mapError(error =>
          ServiceError.InternalServerError
            .UnexpectedError("Failed to read file content for AI extraction", Some(error))
        )

    private def normalizedName(name: String): String = name.toLowerCase(Locale.ROOT)

    private def mergeExtractCustomersResponses(
        responses: NonEmptyChunk[ExtractCustomersResponse]
    ): ExtractCustomersResponse = {
      val responseList         = responses.toChunk.toList
      val individualCandidates = responseList.flatMap(_.customerIndividualCandidates)
      val businessCandidates   = responseList.flatMap(_.customerBusinessCandidates)
      val individualNameCounts = individualCandidates.groupMapReduce(candidate =>
        normalizedName(candidate.candidate.fullName.value)
      )(_ => 1)(_ + _)
      val businessNameCounts = businessCandidates.groupMapReduce(candidate =>
        normalizedName(candidate.candidate.businessName.value)
      )(_ => 1)(_ + _)
      val unidentifiedEntriesSummary = responseList
        .flatMap(_.unidentifiedEntriesSummary)
        .mkString("\n")

      ExtractCustomersResponse(
        entriesIdentified = responseList.map(_.entriesIdentified).sum,
        entriesProcessed = responseList.map(_.entriesProcessed).sum,
        customerIndividualCandidates = individualCandidates.map(candidate =>
          candidate.copy(
            isDuplicate = individualNameCounts(normalizedName(candidate.candidate.fullName.value)) > 1
          )
        ),
        customerBusinessCandidates = businessCandidates.map(candidate =>
          candidate.copy(
            isDuplicate = businessNameCounts(normalizedName(candidate.candidate.businessName.value)) > 1
          )
        ),
        unidentifiedEntriesSummary = Option.when(unidentifiedEntriesSummary.nonEmpty)(unidentifiedEntriesSummary),
      )
    }

    override def extractFromImage[A](
        imageScannedPath: FileScannedPath,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      ZIO
        .attemptBlocking(Files.readAllBytes(imageScannedPath.value))
        .mapError(error =>
          ServiceError.InternalServerError.UnexpectedError("Failed to read image for AI extraction", Some(error))
        )
        .flatMap(extractFromImageBytes[A](_, supportedMediaType, instructions))

    override def extractFromCsv(
        csvValidatedPath: CsvValidatedPath,
        instructions: String,
    ): IO[ServiceError, ExtractCustomersResponse] =
      csvBatches(csvValidatedPath)
        .flatMap(csvBatchTexts =>
          ZIO
            .foreachPar(csvBatchTexts)(csvBatchText =>
              sendAndDecode[ExtractCustomersResponse](
                ChatCompletionModel.GPT56Luna,
                instructions,
                Content.TextContent(csvBatchText),
              )
            )
            .withParallelism(aiClientConfig.csvBatchParallelism)
        )
        .map(mergeExtractCustomersResponses)

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
