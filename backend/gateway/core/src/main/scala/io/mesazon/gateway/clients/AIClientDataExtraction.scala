package io.mesazon.gateway.clients

import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.mesazon.domain.gateway.{NoteCompactionOutput, ServiceError, SupportedMediaType}
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.{ai, OpenAIJsonSchema}
import io.mesazon.gateway.utils.*
import org.apache.commons.csv.*
import sttp.ai.openai.OpenAI
import sttp.ai.openai.OpenAIExceptions.OpenAIException
import sttp.ai.openai.requests.responses.ResponsesRequestBody.Input.{InputContentItem, InputMessage}
import sttp.ai.openai.requests.responses.ResponsesRequestBody.{Format, ReasoningConfig, TextConfig}
import sttp.ai.openai.requests.responses.{ResponsesModel, ResponsesRequestBody, ResponsesResponseBody}
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
      processingMessageOpt: Option[String] = None,
  )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

  def extractFromPdf[A](
      pdfScannedPath: FileScannedPath,
      pdfFileNameDeclared: String,
      instructions: String,
      processingMessageOpt: Option[String] = None,
  )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

  def extractFromCsv[A](
      csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
      instructions: String,
      processingMessageOpt: Option[String] = None,
  )(using
      OpenAIJsonSchema[A],
      JsonValueCodec[A],
  ): ZIO[Scope, ServiceError, NonEmptyChunk[A]]

  def noteCompaction(notes: List[String], instructions: String): IO[ServiceError, NoteCompactionOutput]
}

object AIClientDataExtraction {

  private final class AIClientDataExtractionImpl(
      openAI: OpenAI,
      backend: Backend[Task],
      aiClientDataExtractionConfig: AIClientDataExtractionConfig,
  ) extends AIClientDataExtraction {

    type CSVRecordWithIndex = (csvRecord: CSVRecord, index: Long)

    private inline val dataRowNumberOffset      = 1L
    private inline val responseFormatName       = "ai_client_response"
    private inline val inputMessageRoleUser     = "user"
    private inline val inputImageDetailHigh     = "high"
    private inline val responsesStatusCompleted = "completed"

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

    private def sendWithRetryPolicy[B](
        request: Request[Either[OpenAIException, B]],
        requestTimeout: Duration,
    ): IO[ServiceError, B] =
      request
        .readTimeout(requestTimeout.toScala)
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

    private def sendAndDecode[A](
        model: String,
        reasoningEffort: String,
        requestTimeout: Duration,
        instructions: String,
        sourceInputContentItems: List[InputContentItem],
        processingMessageOpt: Option[String],
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      for {
        response <- sendWithRetryPolicy(
          openAI.createModelResponse(
            ResponsesRequestBody(
              model = Some(ResponsesModel.CustomResponsesModel(model)),
              instructions = Some(instructions),
              input = Some(
                Right(
                  List(
                    InputMessage(content = sourceInputContentItems, role = inputMessageRoleUser, status = None)
                  ) ++ processingMessageOpt.map(processingMessage =>
                    InputMessage(
                      content = List(InputContentItem.InputText(processingMessage)),
                      role = inputMessageRoleUser,
                      status = None,
                    )
                  )
                )
              ),
              reasoning = Some(ReasoningConfig(effort = Some(reasoningEffort))),
              store = Some(false),
              text = Some(
                TextConfig(
                  format = Some(
                    Format.JsonSchema(
                      name = responseFormatName,
                      strict = Some(true),
                      schema = Some(summon[OpenAIJsonSchema[A]].schema),
                      description = None,
                    )
                  )
                )
              ),
            )
          ),
          requestTimeout,
        )
        outputText <- ZIO
          .fromOption(
            Option
              .when(response.status == responsesStatusCompleted)(response.output)
              .flatMap(_.collectFirst { case outputMessage: ResponsesResponseBody.OutputItem.Message =>
                outputMessage
              })
              .flatMap(_.content.collectFirst { case outputText: ResponsesResponseBody.OutputContent.OutputText =>
                outputText.text
              })
          )
          .orElseFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to parse AI response with status [${response.status}]: ${response.output.mkString("\n")}"
            )
          )
        result <- ZIO
          .attempt(readFromString[A](outputText))
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError(s"Failed to parse AI response $outputText", Some(error))
          )
      } yield result

    private def extractFromImageBytes[A](
        imageBytes: Array[Byte],
        supportedMediaType: SupportedMediaType,
        instructions: String,
        processingMessageOpt: Option[String],
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] = {
      val imageBase64 = Base64.getEncoder.encodeToString(imageBytes)
      val imageMime   = supportedMediaType.mimes.head
      sendAndDecode[A](
        aiClientDataExtractionConfig.imageModel,
        aiClientDataExtractionConfig.imageReasoningEffort,
        aiClientDataExtractionConfig.requestTimeout,
        instructions,
        List(
          InputContentItem.InputImage(
            detail = inputImageDetailHigh,
            fileId = None,
            imageUrl = Some(s"data:$imageMime;base64,$imageBase64"),
          )
        ),
        processingMessageOpt,
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

    override def extractFromImage[A](
        imageScannedPath: FileScannedPath,
        supportedMediaType: SupportedMediaType,
        instructions: String,
        processingMessageOpt: Option[String],
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      ZIO
        .attemptBlocking(Files.readAllBytes(imageScannedPath.value))
        .mapError(error =>
          ServiceError.InternalServerError.UnexpectedError("Failed to read image for AI extraction", Some(error))
        )
        .flatMap(extractFromImageBytes[A](_, supportedMediaType, instructions, processingMessageOpt))

    override def extractFromPdf[A](
        pdfScannedPath: FileScannedPath,
        pdfFileNameDeclared: String,
        instructions: String,
        processingMessageOpt: Option[String],
    )(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A] =
      for {
        pdfBytes <- ZIO
          .attemptBlocking(Files.readAllBytes(pdfScannedPath.value))
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Failed to read PDF for AI extraction", Some(error))
          )
        pdfBase64 = Base64.getEncoder.encodeToString(pdfBytes)
        pdfMime   = SupportedMediaType.PDF.mimes.head
        result <- sendAndDecode[A](
          aiClientDataExtractionConfig.pdfModel,
          aiClientDataExtractionConfig.pdfReasoningEffort,
          aiClientDataExtractionConfig.requestTimeout,
          instructions,
          List(
            InputContentItem.InputFile(
              fileData = Some(s"data:$pdfMime;base64,$pdfBase64"),
              fileId = None,
              fileUrl = None,
              filename = Some(pdfFileNameDeclared),
            )
          ),
          processingMessageOpt,
        )
      } yield result

    override def extractFromCsv[A](
        csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
        instructions: String,
        processingMessageOpt: Option[String],
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
                  aiClientDataExtractionConfig.csvModel,
                  aiClientDataExtractionConfig.csvReasoningEffort,
                  aiClientDataExtractionConfig.requestTimeout,
                  instructions,
                  List(InputContentItem.InputText(csvRecordsBatchText)),
                  processingMessageOpt,
                )
              )
            }
            .runCollect
      } yield NonEmptyChunk(extractFromCsvResultsChunk.head, extractFromCsvResultsChunk.tail*)

    override def noteCompaction(notes: List[String], instructions: String): IO[ServiceError, NoteCompactionOutput] =
      sendAndDecode[NoteCompactionOutput](
        aiClientDataExtractionConfig.noteCompactionModel,
        aiClientDataExtractionConfig.noteCompactionReasoningEffort,
        aiClientDataExtractionConfig.noteCompactionRequestTimeout,
        instructions,
        List(InputContentItem.InputText(writeToString(notes)(using ai.noteCompactionNotesCodec))),
        None,
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
