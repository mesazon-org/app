package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.{ai, OpenAIJsonSchema}
import io.mesazon.gateway.utils.FileScannedPath
import io.mesazon.testkit.base.*
import io.mesazon.wiremock.WiremockClient
import io.mesazon.wiremock.WiremockClient.WiremockClientConfig
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.StatusCode
import sttp.tapir.Schema
import zio.*
import zio.stream.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AIClientDataExtractionSpec extends ZWordSpecBase, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/wiremock.yaml"

  override def exposedServices: Set[ExposedService] = WiremockClient.ExposedServices

  private val imageBytes = Array[Byte](1, 2, 3, 4, 5)

  private def imageScannedPath(): FileScannedPath = {
    val path = Files.createTempFile("ai-client-data-extraction-spec-image-", ".jpg")
    Files.write(path, imageBytes)
    path.toFile.deleteOnExit()
    FileScannedPath(path)
  }

  private def csvRecordStream(csvText: String): ZStream[Scope, ServiceError, CSVRecord] = {
    val csvPath = Files.createTempFile("ai-client-data-extraction-spec-csv-", ".csv")
    Files.writeString(csvPath, csvText, StandardCharsets.UTF_8)
    csvPath.toFile.deleteOnExit()

    ZStream
      .acquireReleaseWith(
        ZIO
          .attemptBlocking(
            CSVParser.parse(Files.newBufferedReader(csvPath, StandardCharsets.UTF_8), CSVFormat.DEFAULT)
          )
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(error)))
      )(csvParser => ZIO.attemptBlocking(csvParser.close()).ignoreLogged)
      .flatMap(csvParser =>
        ZStream
          .blocking(ZStream.fromJavaIterator(csvParser.iterator()))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(error)))
      )
  }

  private lazy val extractedTestResultSchema: Schema[ExtractedTestResult] = Schema.derived[ExtractedTestResult]

  case class ExtractedTestResult(value: String)
  case class Context(aiClientDataExtractionConfig: AIClientDataExtractionConfig, wiremockClient: WiremockClient)

  given extractedTestResultOpenAIJsonSchema: OpenAIJsonSchema[ExtractedTestResult] =
    ai.fromTapir(extractedTestResultSchema)
  given JsonValueCodec[ExtractedTestResult] = JsonCodecMaker.make[ExtractedTestResult]

  def withContext[A](f: Context => A): A = withContainers { container =>
    val wiremockClientConfig = WiremockClientConfig.from(container)
    val wiremockClient       = ZIO
      .service[WiremockClient]
      .provide(
        WiremockClient.live,
        ZLayer.succeed(wiremockClientConfig),
        HttpClientZioBackend.layer(),
      )
      .zioValue

    val aiClientDataExtractionConfig = AIClientDataExtractionConfig(
      scheme = "http",
      host = wiremockClientConfig.host,
      port = wiremockClientConfig.port,
      apiKey = "test-api-key",
      requestTimeout = Duration.fromSeconds(60),
      sendMaxRetries = 2,
      sendRetryDelay = Duration.fromMillis(10),
      csvBatchMaxDataRows = 50,
      csvBatchParallelism = 3,
    )

    f(Context(aiClientDataExtractionConfig, wiremockClient))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      val healthCheckStatusResponse = wiremockClient.healthCheck.zioValue

      healthCheckStatusResponse.code shouldBe StatusCode.Ok
      healthCheckStatusResponse.body.status shouldBe "healthy"
    }
  }

  override def afterEach(): Unit = withContext { context =>
    import context.*

    super.afterEach()

    eventually {
      wiremockClient.reset.zioValue.code shouldBe StatusCode.Ok
    }
  }

  "AIClientDataExtraction" when {
    "extractFromImage" should {
      "successfully extract a structured response from an image" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val extractedTestResult = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_SUCCESS",
          )
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("extracted-value")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 1

        extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
        extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
        extractFromImageRequestMappings(0).count shouldBe 1
      }

      "successfully retry a rate-limited request and eventually decode the response" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val extractedTestResult = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_RETRY_SUCCESS",
          )
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("retried-value")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the image path cannot be read before sending" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val unreadableImagePath = FileScannedPath(Files.createTempDirectory("ai-client-spec-missing-image-"))

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            unreadableImagePath,
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_PATH_READ_FAILURE",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Failed to read image for AI extraction"

        val extractFromImageRequestMappings = wiremockClient.requestsDetails.zioValue

        extractFromImageRequestMappings shouldBe Seq.empty
      }

      "fail with an UnexpectedError when the AI service returns an error" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_ERROR",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service rejects the request after one attempt" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromImage[ExtractedTestResult](
              imageScannedPath(),
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_BAD_REQUEST",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val extractFromImageRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromImageRequestMappings.size shouldBe 1
          extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
          extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI connection resets after three attempts" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_CONNECTION_RESET",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when each response times out after three attempts" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigTimeout =
          aiClientDataExtractionConfig.copy(requestTimeout = Duration.fromMillis(100))

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigTimeout),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_TIMEOUT",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service returns undecodable structured-output JSON" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromImage[ExtractedTestResult](
              imageScannedPath(),
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_MALFORMED",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val extractFromImageRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromImageRequestMappings.size shouldBe 1
          extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
          extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI response does not match the expected shape" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_UNEXPECTED_SHAPE",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message should startWith("Failed to parse AI response")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 1
        extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
        extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
        extractFromImageRequestMappings(0).count shouldBe 1
      }
    }

    "extractFromCsv" should {
      "decode one AI result per batch, in batch order" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            (1 to 101).map(index => s"Customer $index,customer$index@example.com\n").mkString

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(3)(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 3
        extractFromCsvRequestMappings.map(_.mapping.method).distinct shouldBe Seq("POST")
        extractFromCsvRequestMappings.map(_.mapping.url).distinct shouldBe Seq("/v1/chat/completions")
        extractFromCsvRequestMappings.map(_.count).distinct shouldBe Seq(3)
      }

      "split records across multiple AI requests once they exceed the batch size limit" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigSmallBatches = aiClientDataExtractionConfig.copy(csvBatchMaxDataRows = 10)

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigSmallBatches),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            (1 to 25).map(index => s"Customer $index,customer$index@example.com\n").mkString

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(3)(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 3
        extractFromCsvRequestMappings.map(_.mapping.method).distinct shouldBe Seq("POST")
        extractFromCsvRequestMappings.map(_.mapping.url).distinct shouldBe Seq("/v1/chat/completions")
        extractFromCsvRequestMappings.map(_.count).distinct shouldBe Seq(3)
      }

      "keep a blank row in its batch instead of excluding it before batching" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigSmallBatches = aiClientDataExtractionConfig.copy(csvBatchMaxDataRows = 2)

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigSmallBatches),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            "Alice,alice@example.com\n" +
            ",\n" +
            "Bob,bob@example.com\n"

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(2)(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 2
        extractFromCsvRequestMappings.map(_.mapping.method).distinct shouldBe Seq("POST")
        extractFromCsvRequestMappings.map(_.mapping.url).distinct shouldBe Seq("/v1/chat/completions")
        extractFromCsvRequestMappings.map(_.count).distinct shouldBe Seq(2)
      }

      "send each row's real file position in a leading Row Number column, blank rows included" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val csvText =
            "Full Name,Email\n" +
              "Alice,alice@example.com\n" +
              ",\n" +
              "Bob,bob@example.com\n"

          val extractFromCsvResults = ZIO
            .scoped(
              aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
                csvRecordStream(csvText),
                "AI_CLIENT_SPEC_CSV_ROW_NUMBER_SUCCESS",
              )
            )
            .zioValue

          extractFromCsvResults.toChunk.toList shouldBe List(ExtractedTestResult("row-number-result"))

          val extractFromCsvRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromCsvRequestMappings.size shouldBe 1
          extractFromCsvRequestMappings(0).mapping.method shouldBe "POST"
          extractFromCsvRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          extractFromCsvRequestMappings(0).count shouldBe 1
      }

      "send exactly one batch for a CSV record stream with only a header row" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val csvText = "Full Name,Email\n"

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 1
        extractFromCsvRequestMappings(0).mapping.method shouldBe "POST"
        extractFromCsvRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
        extractFromCsvRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the CSV record stream fails before any batch is sent" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val serviceErrorStreamFailure =
            ServiceError.InternalServerError.UnexpectedError("CSV record stream failed upstream", None)

          val serviceError = ZIO
            .scoped(
              aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
                ZStream.fail(serviceErrorStreamFailure),
                "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
              )
            )
            .zioError

          serviceError shouldBe serviceErrorStreamFailure

          wiremockClient.requestsDetails.zioValue shouldBe Seq.empty
      }
    }

    "noteCompaction" should {
      "successfully compact identified/processed counts, row lists, and notes into one message" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val noteCompactionOutput = aiClientDataExtraction
            .noteCompaction(
              entriesIdentified = 5L,
              entriesProcessed = 3L,
              emptyEntryRows = List(2L, 4L),
              unidentifiedEntryRows = List(6L),
              unidentifiedEntriesNotes = List("A raw batch note"),
              instructions = "NOTE_COMPACTION_SPEC_SUCCESS",
            )
            .zioValue

          noteCompactionOutput shouldBe NoteCompactionOutput(note = "Two rows were blank and one had no readable name.")

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe 1
          noteCompactionRequestMappings(0).mapping.method shouldBe "POST"
          noteCompactionRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          noteCompactionRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI service returns an error" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .noteCompaction(
            entriesIdentified = 5L,
            entriesProcessed = 3L,
            emptyEntryRows = List(2L, 4L),
            unidentifiedEntryRows = List(6L),
            unidentifiedEntriesNotes = List("A raw batch note"),
            instructions = "NOTE_COMPACTION_SPEC_ERROR",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val noteCompactionRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        noteCompactionRequestMappings.size shouldBe 3
        noteCompactionRequestMappings.foreach { noteCompactionRequestMapping =>
          noteCompactionRequestMapping.mapping.method shouldBe "POST"
          noteCompactionRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          noteCompactionRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service rejects the request after one attempt" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .noteCompaction(
              entriesIdentified = 5L,
              entriesProcessed = 3L,
              emptyEntryRows = List(2L, 4L),
              unidentifiedEntryRows = List(6L),
              unidentifiedEntriesNotes = List("A raw batch note"),
              instructions = "NOTE_COMPACTION_SPEC_BAD_REQUEST",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe 1
          noteCompactionRequestMappings(0).mapping.method shouldBe "POST"
          noteCompactionRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          noteCompactionRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI service returns undecodable structured-output JSON" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .noteCompaction(
              entriesIdentified = 5L,
              entriesProcessed = 3L,
              emptyEntryRows = List(2L, 4L),
              unidentifiedEntryRows = List(6L),
              unidentifiedEntriesNotes = List("A raw batch note"),
              instructions = "NOTE_COMPACTION_SPEC_MALFORMED",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe 1
          noteCompactionRequestMappings(0).mapping.method shouldBe "POST"
          noteCompactionRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          noteCompactionRequestMappings(0).count shouldBe 1
      }
    }
  }
}
