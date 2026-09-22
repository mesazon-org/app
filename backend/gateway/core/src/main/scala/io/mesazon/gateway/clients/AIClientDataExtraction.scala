package io.mesazon.gateway.clients

import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.mesazon.domain.gateway.{NoteCompactionOutput, ServiceError, SupportedMediaType}
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.utils.*
import org.apache.commons.csv.*
import sttp.ai.openai.OpenAI
import sttp.ai.openai.OpenAIExceptions.OpenAIException
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel, ResponseFormat}
import sttp.ai.openai.requests.completions.chat.message.*
import sttp.client4.*
import sttp.model.StatusCode
import zio.*
import zio.stream.*

import java.io.StringWriter
import java.nio.file.Files
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.Using

trait AIClientDataExtraction {
  def extractFromImage[A](
      imageScannedPath: FileScannedPath,
      supportedMediaType: SupportedMediaType,
      instructions: String,
  )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

  def extractFromCsv[A](
      csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
      instructions: String,
  )(using
      OpenAIJsonSchema[A],
      JsonValueCodec[A],
  ): ZIO[Scope, ServiceError, NonEmptyChunk[A]]

  def noteCompaction(
      entriesIdentified: Long,
      entriesProcessed: Long,
      emptyEntryRows: List[Long],
      unidentifiedEntryRows: List[Long],
      unidentifiedEntriesNotes: List[String],
      instructions: String,
  ): IO[ServiceError, NoteCompactionOutput]
}

object AIClientDataExtraction {

  private final class AIClientDataExtractionImpl(
      openAI: OpenAI,
      backend: Backend[Task],
      aiClientDataExtractionConfig: AIClientDataExtractionConfig,
  ) extends AIClientDataExtraction {

    type CSVRecordWithIndex = (csvRecord: CSVRecord, index: Long)

    inline private val dataRowNumberOffset = 1L

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
          .readTimeout(aiClientDataExtractionConfig.requestTimeout.toScala)
          .send(backend)
          .map(_.body)
          .absolve
          .retry(
            Schedule.recurWhile[Throwable](isRetryableSendError) &&
              Schedule.recurs(aiClientDataExtractionConfig.sendMaxRetries) &&
              Schedule.exponential(aiClientDataExtractionConfig.sendRetryDelay)
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

    private def csvRecordRowNumber(csvRecordIndex: Long): Long =
      csvRecordIndex + dataRowNumberOffset

    private def csvRecordsBatchToText(
        headerValues: Option[CSVRecord],
        csvRecordsDataWithIndexBatch: Chunk[CSVRecordWithIndex],
    ): IO[ServiceError, String] =
      ZIO.attempt {
        val writer = new StringWriter()
        Using.resource(new CSVPrinter(writer, CSVFormat.DEFAULT)) { csvPrinter =>
          headerValues.foreach(csvRecordHeader =>
            csvPrinter.printRecord(("Row Number" :: csvRecordHeader.asScala.toList)*)
          )
          csvRecordsDataWithIndexBatch.foreach { csvRecordDataWithIndex =>
            val csvRecordDataRowNumber = csvRecordRowNumber(csvRecordDataWithIndex.index)
            csvPrinter.printRecord(
              (csvRecordDataRowNumber.toString :: csvRecordDataWithIndex.csvRecord.asScala.toList)*
            )
          }
        }
        writer.toString
      }
        .mapError(error =>
          ServiceError.InternalServerError
            .UnexpectedError("Failed to build CSV batch content for AI extraction", Some(error))
        )

    private def noteCompactionContent(
        entriesIdentified: Long,
        entriesProcessed: Long,
        emptyEntryRows: List[Long],
        unidentifiedEntryRows: List[Long],
        unidentifiedEntriesNotes: List[String],
    ): String =
      s"""Entries identified: $entriesIdentified
         |Entries processed: $entriesProcessed
         |Empty entry rows: ${emptyEntryRows.mkString(", ")}
         |Unidentified entry rows: ${unidentifiedEntryRows.mkString(", ")}
         |Raw notes from each batch:
         |${unidentifiedEntriesNotes.map(note => s"- $note").mkString("\n")}""".stripMargin

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

    override def extractFromCsv[A](
        csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
        instructions: String,
    )(using
        OpenAIJsonSchema[A],
        JsonValueCodec[A],
    ): ZIO[Scope, ServiceError, NonEmptyChunk[A]] =
      for {
        (csvRecordHeaderWithIndexOpt, csvRecordDataWithIndexStream) <-
          csvRecordStream.zipWithIndex.peel(ZSink.head[CSVRecordWithIndex])
        csvRecordHeaderOpt = csvRecordHeaderWithIndexOpt.map(_.csvRecord)
        extractFromCsvResultsChunk <-
          csvRecordDataWithIndexStream
            .grouped(aiClientDataExtractionConfig.csvBatchMaxDataRows)
            .orElseIfEmpty(ZStream.succeed(Chunk.empty[CSVRecordWithIndex]))
            .mapZIOPar(aiClientDataExtractionConfig.csvBatchParallelism) { csvRecordsDataWithIndexBatch =>
              csvRecordsBatchToText(csvRecordHeaderOpt, csvRecordsDataWithIndexBatch).flatMap(csvRecordsBatchText =>
                sendAndDecode[A](
                  ChatCompletionModel.GPT56Luna,
                  instructions,
                  Content.TextContent(csvRecordsBatchText),
                )
              )
            }
            .runCollect
      } yield NonEmptyChunk(extractFromCsvResultsChunk.head, extractFromCsvResultsChunk.tail*)

    override def noteCompaction(
        entriesIdentified: Long,
        entriesProcessed: Long,
        emptyEntryRows: List[Long],
        unidentifiedEntryRows: List[Long],
        unidentifiedEntriesNotes: List[String],
        instructions: String,
    ): IO[ServiceError, NoteCompactionOutput] =
      sendAndDecode[NoteCompactionOutput](
        ChatCompletionModel.GPT54Mini,
        instructions,
        Content.TextContent(
          noteCompactionContent(
            entriesIdentified,
            entriesProcessed,
            emptyEntryRows,
            unidentifiedEntryRows,
            unidentifiedEntriesNotes,
          )
        ),
      )
  }

  val live = ZLayer {
    for {
      aiClientDataExtractionConfig <- ZIO.service[AIClientDataExtractionConfig]
      backend                      <- ZIO.service[Backend[Task]]
    } yield observed(
      new AIClientDataExtractionImpl(
        new OpenAI(aiClientDataExtractionConfig.apiKey, aiClientDataExtractionConfig.baseUri),
        backend,
        aiClientDataExtractionConfig,
      )
    )
  }

  private def observed(client: AIClientDataExtraction): AIClientDataExtraction = client
}
