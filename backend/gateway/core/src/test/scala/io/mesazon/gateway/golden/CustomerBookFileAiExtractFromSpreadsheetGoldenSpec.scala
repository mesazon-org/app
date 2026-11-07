package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, WriterConfig}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.{AIClientDataExtractionConfig, CustomerBookFileServiceConfig}
import io.mesazon.gateway.json.tapir.customerBookFileAiExtractPostResponseCodec
import io.mesazon.gateway.service.{CustomerBookFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{GatewayArbitraries, SttpTestBackend, ZWordSpecBase}
import zio.*
import zio.stream.ZStream

class CustomerBookFileAiExtractFromSpreadsheetGoldenSpec extends ZWordSpecBase, GatewayArbitraries {

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val customerBookFileExtractFileNamesContactBook = List(
    "contact-book-spreadsheet-test-1.csv",
    "contact-book-spreadsheet-test-2.xls",
    "contact-book-spreadsheet-test-3.xlsx",
    "contact-book-spreadsheet-test-4.csv",
  )

  private inline val JsonIndentionStep = 2

  private def buildCustomerBookFileService: CustomerBookFileService[ServiceTask] = ZIO
    .service[CustomerBookFileService[ServiceTask]]
    .provide(
      CustomerBookFileService.local,
      ZLayer.succeed(AppName("gateway-api")),
      CustomerBookFileServiceConfig.live,
      FileScanner.live,
      SpreadsheetTool.live,
      AIClientDataExtraction.live,
      AIClientDataExtractionConfig.live.map(environment =>
        environment.update[AIClientDataExtractionConfig](
          _.copy(
            scheme = "https",
            host = "api.openai.com",
            port = 443,
            apiKey = apiKey,
            requestTimeout = Duration.fromSeconds(180),
            sendMaxRetries = 2,
            sendRetryDelay = Duration.fromSeconds(1),
            csvBatchMaxDataRows = 100,
            csvBatchParallelism = 4,
          )
        )
      ),
      SttpTestBackend.live,
    )
    .zioValue

  "CustomerBookFileService" when {
    "aiExtract" should {
      customerBookFileExtractFileNamesContactBook.foreach { customerBookFileExtractFileNameContactBook =>
        s"extract customer candidates from $customerBookFileExtractFileNameContactBook" in {
          assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          val organizationID                  = arbitrarySample[OrganizationID]
          val customerBookFileExtractFileName =
            CustomerBookFileExtractFileName.assume(customerBookFileExtractFileNameContactBook)
          val customerBookFileExtractByteStream =
            ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}")

          val customerBookFileProcessingMessage = CustomerBookFileProcessingMessage.assume(
            "You will some times surname missing extract also that from email if mathes the first name in email"
          )

          val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
            .aiExtract(
              organizationID,
              customerBookFileExtractFileName,
              Some(customerBookFileProcessingMessage),
              customerBookFileExtractByteStream,
            )
            .zioValue

          info(
            s"${customerBookFileExtractFileName.value} response:\n${writeToString(customerBookFileAiExtractPostResponse, WriterConfig.withIndentionStep(JsonIndentionStep))}"
          )
        }
      }
    }
  }
}
