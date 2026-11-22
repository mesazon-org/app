package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.{AIClientDataExtractionConfig, CustomerBookFileServiceConfig}
import io.mesazon.gateway.golden.performance.{CustomerBookFileAiExtractPostResponsePerformance, GoldenPerformance}
import io.mesazon.gateway.json.tapir.customerBookFileAiExtractPostResponseCodec
import io.mesazon.gateway.service.{CustomerBookFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{GatewayArbitraries, SttpTestBackend, ZWordSpecBase}
import zio.*
import zio.stream.ZStream

import java.nio.file.*

class CustomerBookFileAiExtractUnrelatedContentGoldenSpec extends ZWordSpecBase, GatewayArbitraries {

  private type CustomerBookFileServiceWithConfig = (
      customerBookFileService: CustomerBookFileService[ServiceTask],
      aiClientDataExtractionConfig: AIClientDataExtractionConfig,
  )

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CustomerBookFileAiExtractUnrelatedContentGoldenSpec")

  private def loadCustomerBookFileAiExtractPostResponseGolden(
      customerBookFileExtractAssetFileName: String
  ): CustomerBookFileAiExtractPostResponse = {
    val golden = goldenDirectory.resolve(
      s"${customerBookFileExtractAssetFileName.take(customerBookFileExtractAssetFileName.lastIndexOf('.'))}.json"
    )

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[CustomerBookFileAiExtractPostResponse](Files.readString(golden))
  }

  private def buildCustomerBookFileServiceWithConfig: CustomerBookFileServiceWithConfig = (for {
    customerBookFileService      <- ZIO.service[CustomerBookFileService[ServiceTask]]
    aiClientDataExtractionConfig <- ZIO.service[AIClientDataExtractionConfig]
  } yield (
    customerBookFileService = customerBookFileService,
    aiClientDataExtractionConfig = aiClientDataExtractionConfig,
  ): CustomerBookFileServiceWithConfig)
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
      "report how closely the customer candidates extracted from image-1.jpeg match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("image-1.jpeg")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(
            s"assets-test/CustomerBookFileAiExtractUnrelatedContentGoldenSpec/${customerBookFileExtractFileName.value}"
          )

        val customerBookFileAiExtractPostResponseGolden =
          loadCustomerBookFileAiExtractPostResponseGolden(customerBookFileExtractFileName.value)

        val customerBookFileServiceWithConfig = buildCustomerBookFileServiceWithConfig

        val customerBookFileAiExtractPostResponse = customerBookFileServiceWithConfig.customerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            customerBookFileExtractFileName.value,
            customerBookFileServiceWithConfig.aiClientDataExtractionConfig.imageModel,
            GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
              customerBookFileAiExtractPostResponseGolden,
              customerBookFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the customer candidates extracted from image-2.webp match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("image-2.webp")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(
            s"assets-test/CustomerBookFileAiExtractUnrelatedContentGoldenSpec/${customerBookFileExtractFileName.value}"
          )

        val customerBookFileAiExtractPostResponseGolden =
          loadCustomerBookFileAiExtractPostResponseGolden(customerBookFileExtractFileName.value)

        val customerBookFileServiceWithConfig = buildCustomerBookFileServiceWithConfig

        val customerBookFileAiExtractPostResponse = customerBookFileServiceWithConfig.customerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            customerBookFileExtractFileName.value,
            customerBookFileServiceWithConfig.aiClientDataExtractionConfig.imageModel,
            GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
              customerBookFileAiExtractPostResponseGolden,
              customerBookFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the customer candidates extracted from image-3.png match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("image-3.png")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(
            s"assets-test/CustomerBookFileAiExtractUnrelatedContentGoldenSpec/${customerBookFileExtractFileName.value}"
          )

        val customerBookFileAiExtractPostResponseGolden =
          loadCustomerBookFileAiExtractPostResponseGolden(customerBookFileExtractFileName.value)

        val customerBookFileServiceWithConfig = buildCustomerBookFileServiceWithConfig

        val customerBookFileAiExtractPostResponse = customerBookFileServiceWithConfig.customerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            customerBookFileExtractFileName.value,
            customerBookFileServiceWithConfig.aiClientDataExtractionConfig.imageModel,
            GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
              customerBookFileAiExtractPostResponseGolden,
              customerBookFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the customer candidates extracted from image-4.jpeg match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("image-4.jpeg")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(
            s"assets-test/CustomerBookFileAiExtractUnrelatedContentGoldenSpec/${customerBookFileExtractFileName.value}"
          )

        val customerBookFileAiExtractPostResponseGolden =
          loadCustomerBookFileAiExtractPostResponseGolden(customerBookFileExtractFileName.value)

        val customerBookFileServiceWithConfig = buildCustomerBookFileServiceWithConfig

        val customerBookFileAiExtractPostResponse = customerBookFileServiceWithConfig.customerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            customerBookFileExtractFileName.value,
            customerBookFileServiceWithConfig.aiClientDataExtractionConfig.imageModel,
            GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
              customerBookFileAiExtractPostResponseGolden,
              customerBookFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the customer candidates extracted from pdf-1.pdf match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                    = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName   = CustomerBookFileExtractFileName.assume("pdf-1.pdf")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(
            s"assets-test/CustomerBookFileAiExtractUnrelatedContentGoldenSpec/${customerBookFileExtractFileName.value}"
          )

        val customerBookFileAiExtractPostResponseGolden =
          loadCustomerBookFileAiExtractPostResponseGolden(customerBookFileExtractFileName.value)

        val customerBookFileServiceWithConfig = buildCustomerBookFileServiceWithConfig

        val customerBookFileAiExtractPostResponse = customerBookFileServiceWithConfig.customerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            customerBookFileExtractFileName.value,
            customerBookFileServiceWithConfig.aiClientDataExtractionConfig.pdfModel,
            GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
              customerBookFileAiExtractPostResponseGolden,
              customerBookFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the customer candidates extracted from spreadsheet-1.csv match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("spreadsheet-1.csv")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(
            s"assets-test/CustomerBookFileAiExtractUnrelatedContentGoldenSpec/${customerBookFileExtractFileName.value}"
          )

        val customerBookFileAiExtractPostResponseGolden =
          loadCustomerBookFileAiExtractPostResponseGolden(customerBookFileExtractFileName.value)

        val customerBookFileServiceWithConfig = buildCustomerBookFileServiceWithConfig

        val customerBookFileAiExtractPostResponse = customerBookFileServiceWithConfig.customerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            customerBookFileExtractFileName.value,
            customerBookFileServiceWithConfig.aiClientDataExtractionConfig.csvModel,
            GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
              customerBookFileAiExtractPostResponseGolden,
              customerBookFileAiExtractPostResponse,
            ),
          )
        )
      }
    }
  }
}
