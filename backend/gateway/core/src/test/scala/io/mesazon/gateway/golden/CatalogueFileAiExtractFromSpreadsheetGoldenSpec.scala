package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.{AIClientDataExtractionConfig, CatalogueFileServiceConfig}
import io.mesazon.gateway.golden.performance.{CatalogueFileAiExtractPostResponsePerformance, GoldenPerformance}
import io.mesazon.gateway.json.tapir.catalogueFileAiExtractPostResponseCodec
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.service.{CatalogueFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{GatewayArbitraries, SttpTestBackend, ZWordSpecBase}
import zio.*
import zio.stream.ZStream

import java.nio.file.*

class CatalogueFileAiExtractFromSpreadsheetGoldenSpec extends ZWordSpecBase, GatewayArbitraries {

  private type CatalogueFileServiceWithConfig = (
      catalogueFileService: CatalogueFileService[ServiceTask],
      aiClientDataExtractionConfig: AIClientDataExtractionConfig,
  )

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CatalogueFileAiExtractFromSpreadsheetGoldenSpec")

  private def loadCatalogueFileAiExtractPostResponseGolden(
      catalogueFileExtractAssetFileName: String
  ): CatalogueFileAiExtractPostResponse = {
    val golden = goldenDirectory.resolve(
      s"${catalogueFileExtractAssetFileName.take(catalogueFileExtractAssetFileName.lastIndexOf('.'))}.json"
    )

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[CatalogueFileAiExtractPostResponse](Files.readString(golden))
  }

  private def buildCatalogueFileServiceWithConfig: CatalogueFileServiceWithConfig = (for {
    catalogueFileService         <- ZIO.service[CatalogueFileService[ServiceTask]]
    aiClientDataExtractionConfig <- ZIO.service[AIClientDataExtractionConfig]
  } yield (
    catalogueFileService = catalogueFileService,
    aiClientDataExtractionConfig = aiClientDataExtractionConfig,
  ): CatalogueFileServiceWithConfig)
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
            csvBatchMaxDataRows = 100,
            csvBatchParallelism = 4,
          )
        )
      ),
      SttpTestBackend.live,
    )
    .zioValue

  "CatalogueFileService" when {
    "aiExtract" should {
      "report how closely the catalogue candidates extracted from spreadsheet-1.csv match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName.assume("spreadsheet-1.csv")
        val catalogueFileExtractByteStream = ZStream.fromResource(
          s"assets-test/CatalogueFileAiExtractFromSpreadsheetGoldenSpec/${catalogueFileExtractFileName.value}"
        )

        val catalogueFileAiExtractPostResponseGolden =
          loadCatalogueFileAiExtractPostResponseGolden(catalogueFileExtractFileName.value)

        val catalogueFileServiceWithConfig = buildCatalogueFileServiceWithConfig

        val catalogueFileAiExtractPostResponse = catalogueFileServiceWithConfig.catalogueFileService
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            catalogueFileExtractFileName.value,
            catalogueFileServiceWithConfig.aiClientDataExtractionConfig.csvModel,
            GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
              catalogueFileAiExtractPostResponseGolden,
              catalogueFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the catalogue candidates extracted from spreadsheet-2.xls match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName.assume("spreadsheet-2.xls")
        val catalogueFileExtractByteStream = ZStream.fromResource(
          s"assets-test/CatalogueFileAiExtractFromSpreadsheetGoldenSpec/${catalogueFileExtractFileName.value}"
        )

        val catalogueFileAiExtractPostResponseGolden =
          loadCatalogueFileAiExtractPostResponseGolden(catalogueFileExtractFileName.value)

        val catalogueFileServiceWithConfig = buildCatalogueFileServiceWithConfig

        val catalogueFileAiExtractPostResponse = catalogueFileServiceWithConfig.catalogueFileService
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            catalogueFileExtractFileName.value,
            catalogueFileServiceWithConfig.aiClientDataExtractionConfig.csvModel,
            GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
              catalogueFileAiExtractPostResponseGolden,
              catalogueFileAiExtractPostResponse,
            ),
          )
        )
      }

      "report how closely the catalogue candidates extracted from spreadsheet-3.xlsx match the golden" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName.assume("spreadsheet-3.xlsx")
        val catalogueFileExtractByteStream = ZStream.fromResource(
          s"assets-test/CatalogueFileAiExtractFromSpreadsheetGoldenSpec/${catalogueFileExtractFileName.value}"
        )

        val catalogueFileAiExtractPostResponseGolden =
          loadCatalogueFileAiExtractPostResponseGolden(catalogueFileExtractFileName.value)

        val catalogueFileServiceWithConfig = buildCatalogueFileServiceWithConfig

        val catalogueFileAiExtractPostResponse = catalogueFileServiceWithConfig.catalogueFileService
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        info(
          GoldenPerformance.renderReport(
            catalogueFileExtractFileName.value,
            catalogueFileServiceWithConfig.aiClientDataExtractionConfig.csvModel,
            GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
              catalogueFileAiExtractPostResponseGolden,
              catalogueFileAiExtractPostResponse,
            ),
          )
        )
      }
    }
  }
}
