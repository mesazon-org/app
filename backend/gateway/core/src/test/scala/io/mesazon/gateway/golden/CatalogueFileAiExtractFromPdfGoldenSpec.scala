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

class CatalogueFileAiExtractFromPdfGoldenSpec extends ZWordSpecBase, GatewayArbitraries {

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
      ZLayer.succeed(mock[CatalogueRepository]),
      FileScanner.live,
      ZLayer.succeed(mock[ImageProcessing]),
      SpreadsheetTool.live,
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
          )
        )
      ),
      SttpTestBackend.live,
    )
    .zioValue

  "CatalogueFileService" when {
    "aiExtract" should {
      "extract catalogue candidates from Catalogue-Price-List.PDF" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName.assume("Catalogue-Price-List.PDF")
        val catalogueFileExtractByteStream = ZStream.fromResource("assets/catalogue-file-ai-extract-3.pdf")

        val catalogueFileAiExtractPostResponse = buildCatalogueFileService
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        info(
          s"${catalogueFileExtractFileName.value} response:\n${writeToString(catalogueFileAiExtractPostResponse, WriterConfig.withIndentionStep(JsonIndentionStep))}"
        )
      }
    }
  }
}
