package io.mesazon.gateway.golden

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.config.AIClientConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.extractCustomersResponseCodec
import io.mesazon.gateway.service.FileService
import io.mesazon.gateway.utils.{FileScannedPath, SpreadsheetTool}
import io.mesazon.testkit.base.ZWordSpecBase
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*

import java.nio.file.Path

/** Manual-only check against the real OpenAI API: converts each Excel fixture to validated CSV through
  * `SpreadsheetTool`, sends that CSV through the real `AIClient`, and asserts the complete captured golden response.
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
          requestTimeout = Duration.fromSeconds(60),
          sendMaxRetries = 2,
          sendRetryDelay = Duration.fromSeconds(1),
        )
      ),
      HttpClientZioBackend.layer(),
    )
    .zioValue

  private val extractCustomersResponseExpected = ExtractCustomersResponse(
    entriesIdentified = 2L,
    entriesProcessed = 2L,
    customerIndividualCandidates = List(
      ExtractCustomerIndividualData(
        candidate = ExtractCustomerIndividual(
          fullName = CustomerFullName.assume("John Smith"),
          emails = List(
            ExtractCustomerEmailEntry(
              email = CustomerEmail.assume("john.smith@example.com"),
              isDefault = true,
            )
          ),
          phoneNumbers = List.empty,
          addressLine1 = None,
          addressLine2 = None,
          city = None,
          postalCode = None,
          country = None,
        ),
        isDuplicate = false,
        extractionNotes = None,
      ),
      ExtractCustomerIndividualData(
        candidate = ExtractCustomerIndividual(
          fullName = CustomerFullName.assume("Jane Doe"),
          emails = List(
            ExtractCustomerEmailEntry(
              email = CustomerEmail.assume("jane.doe@example.com"),
              isDefault = true,
            )
          ),
          phoneNumbers = List.empty,
          addressLine1 = None,
          addressLine2 = None,
          city = None,
          postalCode = None,
          country = None,
        ),
        isDuplicate = false,
        extractionNotes = None,
      ),
    ),
    customerBusinessCandidates = List.empty,
    unidentifiedEntriesSummary = None,
  )

  private val spreadsheetCases = List(
    ("contact-book-test-spreadsheet-2.xls", SupportedMediaType.XLS),
    ("contact-book-test-spreadsheet-3.xlsx", SupportedMediaType.XLSX),
  )

  "AIClient" when {
    "extractFromCsv after SpreadsheetTool conversion" should {
      spreadsheetCases.foreach { case (fileName, supportedMediaType) =>
        s"extract customers from $fileName" in {
          assume(
            apiKey.nonEmpty,
            "Fill in a real OpenAI API key in ExtractCustomersFromSpreadsheetGoldenSpec.apiKey to run this manually",
          )

          val aiClient       = buildAIClient
          val spreadsheetTool = ZIO
            .service[SpreadsheetTool]
            .provide(SpreadsheetTool.live)
            .zioValue

          val extractCustomersResponse = ZIO
            .scoped(for {
              spreadsheetScannedPath <- ZIO.succeed(
                FileScannedPath(Path.of(getClass.getResource(s"/assets/$fileName").toURI))
              )
              csvValidatedPath <- spreadsheetTool.validateAndConvertToCsv(
                spreadsheetScannedPath,
                supportedMediaType,
              )
              response <- aiClient.extractFromCsv[ExtractCustomersResponse](
                csvValidatedPath,
                FileService.extractCustomersFromFileInstructions,
              )
            } yield response)
            .zioValue

          info(s"$fileName => $extractCustomersResponse")

          extractCustomersResponse shouldBe extractCustomersResponseExpected
        }
      }
    }
  }
}
