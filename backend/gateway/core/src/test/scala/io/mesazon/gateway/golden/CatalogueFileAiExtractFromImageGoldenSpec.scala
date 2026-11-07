package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, WriterConfig}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.{AIClientDataExtractionConfig, CatalogueFileServiceConfig}
import io.mesazon.gateway.json.tapir.catalogueFileAiExtractPostResponseCodec
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.service.{CatalogueFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{GatewayArbitraries, SttpTestBackend, ZWordSpecBase}
import zio.*
import zio.stream.ZStream

class CatalogueFileAiExtractFromImageGoldenSpec extends ZWordSpecBase, GatewayArbitraries {

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private inline val JsonIndentionStep = 2

  private def buildCatalogueFileService: CatalogueFileService[ServiceTask] = ZIO
    .service[CatalogueFileService[ServiceTask]]
    .provide(
      CatalogueFileService.local,
      ZLayer.succeed(AppName("gateway-api")),
      CatalogueFileServiceConfig.live,
      FileScanner.live,
      SpreadsheetTool.live,
      ZLayer.succeed(mock[ImageProcessing]),
      ZLayer.succeed(mock[CatalogueRepository]),
      ZLayer.succeed(mock[S3ClientOrganizationMedia]),
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

  "CatalogueFileService" when {
    "aiExtract" should {
      "extract catalogue candidates from JPEG" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName.assume("catalogue-file-ai-extract-1.jpeg")
        val catalogueFileExtractByteStream = ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}")
        val catalogueFileProcessingMessage = CatalogueFileProcessingMessage.assume(
          "ignore cost, each item now is represente with 1 kg but I want a single item with 12 kg per box, keep fruits with 1 kg"
        )

        val catalogueFileAiExtractPostResponse = buildCatalogueFileService
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            Some(catalogueFileProcessingMessage),
            catalogueFileExtractByteStream,
          )
          .zioValue

        info(
          s"${catalogueFileExtractFileName.value} response:\n${writeToString(catalogueFileAiExtractPostResponse, WriterConfig.withIndentionStep(JsonIndentionStep))}"
        )
      }

//      "extract catalogue candidates from WebP" in {
//        assume(
//          apiKey.nonEmpty,
//          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
//        )
//
//        val organizationID = arbitrarySample[OrganizationID]
//        val catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-test-2.webp")
//        val catalogueFileExtractByteStream = ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}")
//
//        val catalogueFileAiExtractPostResponse = buildCatalogueFileService
//          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
//          .zioValue
//
//        info(
//          s"${catalogueFileExtractFileName.value} response:\n${writeToString(catalogueFileAiExtractPostResponse, WriterConfig.withIndentionStep(JsonIndentionStep))}"
//        )
//      }
    }
  }
}
