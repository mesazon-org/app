package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, WriterConfig}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.{AIClientDataExtractionConfig, FileServiceConfig}
import io.mesazon.gateway.json.tapir.extractCustomersPostResponseCodec
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.service.{FileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.stream.ZStream

import java.util.UUID

/** Manual-only check against the real OpenAI API: sends each spreadsheet fixture through
  * `FileService.extractCustomers`, the same entry point `POST /extract/customers` calls, exercising the real
  * scan/media-type-detection/spreadsheet-conversion/AI dispatch/batch-merge pipeline end to end. Prints the merged
  * response as indented JSON for manual inspection; it asserts nothing.
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

  private def buildFileService: FileService[ServiceTask] = ZIO
    .service[FileService[ServiceTask]]
    .provide(
      FileService.local,
      ZLayer.succeed(AppName("gateway-api")),
      FileServiceConfig.live,
      FileScanner.live,
      ZLayer.succeed(mock[ImageProcessing]),
      SpreadsheetTool.live,
      ZLayer.succeed(mock[OrganizationManagementRepository]),
      ZLayer.succeed(mock[CatalogueRepository]),
      ZLayer.succeed(mock[S3ClientOrganizationMedia]),
      AIClientDataExtraction.live,
      ZLayer.succeed(
        AIClientDataExtractionConfig(
          scheme = "https",
          host = "api.openai.com",
          port = 443,
          apiKey = apiKey,
          requestTimeout = Duration.fromSeconds(120),
          sendMaxRetries = 2,
          sendRetryDelay = Duration.fromSeconds(1),
          csvBatchMaxDataRows = 50,
          csvBatchParallelism = 3,
        )
      ),
      HttpClientZioBackend.layer(),
    )
    .zioValue

  // contact-book-spreadsheet-test-4.csv is the large, anonymized, real-shaped CSV fixture (the only file in this
  // family not covered by a small synthetic case above it).
  private val spreadsheetCases = List(
    "contact-book-spreadsheet-test-4.csv",
    "contact-book-spreadsheet-test-2.xls",
    "contact-book-spreadsheet-test-3.xlsx",
  )

  "FileService" when {
    "extractCustomers" should {
      spreadsheetCases.foreach { fileName =>
        s"extract customers from $fileName" in {
          assume(
            apiKey.nonEmpty,
            "Fill in a real OpenAI API key in ExtractCustomersFromSpreadsheetGoldenSpec.apiKey to run this manually",
          )

          val organizationID                 = OrganizationID.assume(UUID.randomUUID())
          val extractCustomersFileName       = ExtractCustomersFileName.assume(fileName)
          val extractCustomersFileByteStream = ZStream.fromResource(s"assets/$fileName")

          val extractCustomersPostResponse = buildFileService
            .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
            .zioValue

          info(
            s"$fileName response:\n${writeToString(extractCustomersPostResponse, WriterConfig.withIndentionStep(2))}"
          )
        }
      }
    }
  }
}
