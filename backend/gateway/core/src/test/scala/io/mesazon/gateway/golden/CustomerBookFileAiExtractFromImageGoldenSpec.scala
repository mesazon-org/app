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

class CustomerBookFileAiExtractFromImageGoldenSpec extends ZWordSpecBase, GatewayArbitraries {

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val customerBookFileExtractFileNamesContactBook = List(
    "contact-book-image-test-1.png",
    "contact-book-image-test-2.png",
    "contact-book-image-test-3.png",
    "contact-book-image-test-4.png",
    "contact-book-image-test-5.png",
    "contact-book-image-test-6.png",
    "contact-book-image-test-7.png",
    "contact-book-image-test-8.png",
    "contact-book-image-test-9.png",
    "contact-book-image-test-10.png",
    "contact-book-image-test-11.jpeg",
    "contact-book-image-test-12.webp",
    "contact-book-image-test-13.png",
  )

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
            csvBatchMaxDataRows = 50,
            csvBatchParallelism = 3,
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

          val customerBookFileProcessingMessageOpt = CustomerBookFileProcessingMessage.assume(
            "For each customers create a second entry with 1 suffix"
          )

          val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
            .aiExtract(
              organizationID,
              customerBookFileExtractFileName,
              Some(customerBookFileProcessingMessageOpt),
              customerBookFileExtractByteStream,
            )
            .zioValue

          info(
            s"${customerBookFileExtractFileName.value} response:\n${writeToString(customerBookFileAiExtractPostResponse, WriterConfig.withIndentionStep(2))}"
          )
        }
      }

      "extract customer candidates from contact-book-image-test-1.png following the processing message" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                    = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName   = CustomerBookFileExtractFileName.assume("contact-book-image-test-1.png")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}")
        val customerBookFileProcessingMessage =
          CustomerBookFileProcessingMessage.assume("omit phone numbers, keep everything else")

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(
            organizationID,
            customerBookFileExtractFileName,
            Some(customerBookFileProcessingMessage),
            customerBookFileExtractByteStream,
          )
          .zioValue

        info(
          s"${customerBookFileExtractFileName.value} response:\n${writeToString(customerBookFileAiExtractPostResponse, WriterConfig.withIndentionStep(2))}"
        )
      }
    }

    "aiExtract irrelevant" should {
      "extract customer candidates from contact-book-image-test-14.jpeg" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                  = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-test-14.jpeg")
        val customerBookFileExtractByteStream =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}")

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        info(
          s"${customerBookFileExtractFileName.value} response:\n${writeToString(customerBookFileAiExtractPostResponse, WriterConfig.withIndentionStep(2))}"
        )
      }
    }
  }
}
