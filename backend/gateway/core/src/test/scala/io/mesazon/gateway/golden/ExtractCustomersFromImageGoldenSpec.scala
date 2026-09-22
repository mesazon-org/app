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

/** Manual-only check against the real OpenAI API: sends each sample image in
  * `assets/contact-book-image-test-1.png`..`-10.png` through `FileService.extractCustomers`, the same entry point
  * `POST /extract/customers` calls, exercising the real scan/media-type-detection/AI dispatch pipeline end to end.
  * Prints each returned response as indented JSON for manual inspection; it asserts nothing.
  *
  * Never calls out for real in CI: `apiKey` ships empty, so every case is canceled rather than hitting the real API
  * with a blank key. To run for real, fill in a real key below and invoke this spec directly:
  * {{{
  * sbt "gateway-core/testOnly io.mesazon.gateway.golden.ExtractCustomersFromImageGoldenSpec"
  * }}}
  */
class ExtractCustomersFromImageGoldenSpec extends ZWordSpecBase {

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
          requestTimeout = Duration.fromSeconds(60),
          sendMaxRetries = 2,
          sendRetryDelay = Duration.fromSeconds(1),
          csvBatchMaxDataRows = 50,
          csvBatchParallelism = 3,
        )
      ),
      HttpClientZioBackend.layer(),
    )
    .zioValue

  "FileService" when {
    "extractCustomers" should {
      (1 to 10).foreach { imageNumber =>
        s"extract customers from contact-book-image-test-$imageNumber.png" in {
          assume(
            apiKey.nonEmpty,
            "Fill in a real OpenAI API key in ExtractCustomersFromImageGoldenSpec.apiKey to run this manually",
          )

          val organizationID           = OrganizationID.assume(UUID.randomUUID())
          val extractCustomersFileName = ExtractCustomersFileName.assume(s"contact-book-image-test-$imageNumber.png")
          val extractCustomersFileByteStream =
            ZStream.fromResource(s"assets/contact-book-image-test-$imageNumber.png")

          val extractCustomersPostResponse = buildFileService
            .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
            .zioValue

          info(
            s"contact-book-image-test-$imageNumber.png response:\n${writeToString(extractCustomersPostResponse, WriterConfig.withIndentionStep(2))}"
          )
        }
      }
    }
  }
}
