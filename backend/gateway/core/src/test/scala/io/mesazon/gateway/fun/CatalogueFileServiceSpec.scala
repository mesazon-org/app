package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{AIClientDataExtraction, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.service.{CatalogueFileService, ServiceTask}
import io.mesazon.gateway.utils.{FileScanner, ImageProcessing, SpreadsheetTool}
import io.mesazon.testkit.base.{CatalogueDomainArbitraries, ZWordSpecBase}
import zio.*
import zio.stream.ZStream

class CatalogueFileServiceSpec extends ZWordSpecBase, CatalogueDomainArbitraries {

  "CatalogueFileService" when {
    "extractCatalogueItems" should {
      "return extracted catalogue item candidates for a photo" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.fromResource("assets/organization-image-test-1.jpeg")
        val catalogueItemVariantCandidate  = CatalogueItemVariantCandidate(
          sku = None,
          label = arbitrarySample[Option[CatalogueItemVariantLabel]],
          description = arbitrarySample[Option[CatalogueItemVariantDescription]],
          attributes = arbitrarySample[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]],
          price = arbitrarySample[Option[CatalogueItemVariantPrice]],
          sellingUnit = None,
          missingRequiredSaleUnit = true,
          skuIsDuplicate = false,
          extractionNotes = List(
            "Selling unit is unsupported",
            "SKU is present but unclear",
          ),
        )
        val catalogueFileExtractPostResponseExpected = CatalogueFileExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = arbitrarySample[CatalogueItemName],
              description = arbitrarySample[Option[CatalogueItemDescription]],
              variants = List(catalogueItemVariantCandidate),
              atLeastOneVariant = false,
              nameIsDuplicate = false,
              extractionNotes = List("Item and variant grouping is unclear"),
            )
          ),
          needsAttention = true,
          infoNotes = List("Processed 1 of 1 catalogue entries"),
          extractionNotes = List("The source image is cropped at the bottom"),
        )

        val catalogueFileService = buildCatalogueFileService

        val catalogueFileExtractPostResponse = catalogueFileService
          .extractCatalogueItems(
            organizationID = organizationID,
            catalogueFileExtractFileName = catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt = None,
            catalogueFileExtractByteStream = catalogueFileExtractByteStream,
          )
          .zioValue

        catalogueFileExtractPostResponse shouldBe catalogueFileExtractPostResponseExpected
      }
    }
  }

  trait TestContext {
    val fileServiceConfig = FileServiceConfig(
      fileBytesMax = 20L * 1024 * 1024
    )

    val catalogueRepositoryMock       = mock[CatalogueRepository]
    val fileScannerMock               = mock[FileScanner]
    val imageProcessingMock           = mock[ImageProcessing]
    val spreadsheetToolMock           = mock[SpreadsheetTool]
    val s3ClientOrganizationMediaMock = mock[S3ClientOrganizationMedia]
    val aiClientDataExtractionMock    = mock[AIClientDataExtraction]

    def buildCatalogueFileService: CatalogueFileService[ServiceTask] = ZIO
      .service[CatalogueFileService[ServiceTask]]
      .provide(
        CatalogueFileService.local,
        ZLayer.succeed(fileServiceConfig),
        ZLayer.succeed(catalogueRepositoryMock),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(spreadsheetToolMock),
        ZLayer.succeed(s3ClientOrganizationMediaMock),
        ZLayer.succeed(aiClientDataExtractionMock),
      )
      .zioValue
  }
}
