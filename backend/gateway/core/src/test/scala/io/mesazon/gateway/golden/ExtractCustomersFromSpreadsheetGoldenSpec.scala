package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, WriterConfig}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.config.AIClientConfig
import io.mesazon.gateway.json.tapir.extractCustomersResponseCodec
import io.mesazon.gateway.service.FileService
import io.mesazon.gateway.utils.{FileScannedPath, SpreadsheetTool, TempFile}
import io.mesazon.testkit.base.ZWordSpecBase
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.stream.{ZSink, ZStream}

/** Manual-only check against the real OpenAI API: validates or converts each spreadsheet fixture through
  * `SpreadsheetTool`, sends the resulting CSV through the real `AIClient`, and prints the formatted response for
  * inspection without asserting its contents.
  *
  * Never calls out for real in CI: `apiKey` ships empty, so every case is canceled rather than hitting the real API
  * with a blank key. To run for real, fill in a real key below and invoke this spec directly:
  * {{{
  * sbt "gateway-core/testOnly io.mesazon.gateway.golden.ExtractCustomersFromSpreadsheetGoldenSpec"
  * }}}
  */
class ExtractCustomersFromSpreadsheetGoldenSpec extends ZWordSpecBase {

  private val apiKey =
    ""

  private def buildAIClient: AIClient = ZIO
    .service[AIClient]
    .provide(
      AIClient.live,
      ZLayer.succeed(
        AIClientConfig(
          scheme = "https",
          host = "api.openai.com",
          port = 443,
          apiKey = apiKey,
          requestTimeout = Duration.fromSeconds(120),
          sendMaxRetries = 2,
          sendRetryDelay = Duration.fromSeconds(1),
        )
      ),
      HttpClientZioBackend.layer(),
    )
    .zioValue

  private val spreadsheetCases = List(
    ("contact-book-test-spreadsheet-1.csv", SupportedMediaType.CSV),
    ("contact-book-test-spreadsheet-2.xls", SupportedMediaType.XLS),
    ("contact-book-test-spreadsheet-3.xlsx", SupportedMediaType.XLSX),
  )

  "AIClient" when {
    "extractFromCsv after SpreadsheetTool validation and conversion" should {
      spreadsheetCases.foreach { case (fileName, supportedMediaType) =>
        s"extract customers from $fileName" in {
          assume(
            apiKey.nonEmpty,
            "Fill in a real OpenAI API key in ExtractCustomersFromSpreadsheetGoldenSpec.apiKey to run this manually",
          )

          val aiClient        = buildAIClient
          val spreadsheetTool = ZIO
            .service[SpreadsheetTool]
            .provide(SpreadsheetTool.live)
            .zioValue

          val extractCustomersResponse = ZIO
            .scoped(for {
              spreadsheetTempPath <- TempFile.createScoped("extract-customers-from-spreadsheet-golden-")
              _                   <- ZStream
                .fromResource(s"assets/$fileName")
                .run(ZSink.fromPath(spreadsheetTempPath))
                .orDie
              spreadsheetScannedPath = FileScannedPath(spreadsheetTempPath)
              csvValidatedPath <- spreadsheetTool.validateAndConvertToCsv(
                spreadsheetScannedPath,
                supportedMediaType,
              )
              response <- aiClient.extractFromCsv(
                csvValidatedPath,
                FileService.extractCustomersFromFileInstructions,
              )
            } yield response)
            .zioValue

          info(
            s"$fileName response:\n${writeToString(extractCustomersResponse, WriterConfig.withIndentionStep(2))}"
          )
        }
      }
    }
  }
}
