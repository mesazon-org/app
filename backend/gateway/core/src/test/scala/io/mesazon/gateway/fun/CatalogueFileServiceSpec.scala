package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{AIClientDataExtraction, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.CatalogueFileServiceConfig
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{CatalogueFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{CatalogueDomainArbitraries, ZWordSpecBase}
import org.scalamock.matchers.ArgCapture.CaptureOne
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CatalogueFileServiceSpec extends ZWordSpecBase, CatalogueDomainArbitraries, RepositoryArbitraries {

  "CatalogueFileService" when {
    "uploadCatalogueItemImage" should {
      "successfully upload a catalogue item's image" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          isArchived = false,
        )
        val catalogueItemWithVariantsRow = CatalogueItemWithVariantsRow(
          catalogueItemRow,
          NonEmptyChunk.single(catalogueItemVariantRow),
        )

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/catalogue-image-test-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/catalogue-image-test-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val catalogueItemImageOriginalS3BucketKey   = arbitrarySample[ImageOriginalS3BucketKey]
        val catalogueItemImageNormalizedS3BucketKey = arbitrarySample[ImageNormalizedS3BucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedImageResult =
          (
            imageOriginalS3BucketKey = catalogueItemImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = catalogueItemImageNormalizedS3BucketKey,
          )

        val catalogueItemImageAsset = CatalogueItemImageAsset.assume(
          ImageAsset(
            imageOriginalS3BucketKey = catalogueItemImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = catalogueItemImageNormalizedS3BucketKey,
            imageOriginalFileName = catalogueItemImageOriginalFileName,
          )
        )

        val scannedImageBytes           = "scanned catalogue image".getBytes(StandardCharsets.UTF_8)
        val fileScannedPath             = FileScannedPath(Files.createTempFile("catalogue-file-service-spec-", ".jpeg"))
        val normalizeImageStreamCapture = CaptureOne[FileByteStreamScanned]()

        try {
          Files.write(fileScannedPath.value, scannedImageBytes)

          inSequence(
            catalogueRepositoryMock.getCatalogueItemWithVariants
              .expects(organizationID, catalogueItemID)
              .returningZIO(Some(catalogueItemWithVariantsRow))
              .once(),
            fileScannerMock.scan
              .expects(
                catalogueItemImageByteStream,
                catalogueItemImageOriginalFileName.value,
                SupportedMediaType.images,
                catalogueFileServiceConfig.uploadCatalogueItemImageFileBytesMax,
              )
              .returns(
                ZIO.succeed(
                  (
                    fileScannedPath = fileScannedPath,
                    supportedMediaType = SupportedMediaType.JPEG,
                    fileBytesSize = FileBytesSize.assume(Files.size(fileScannedPath.value)),
                  )
                )
              )
              .once(),
            imageProcessingMock.normalize
              .expects(capture(normalizeImageStreamCapture), SupportedMediaType.images)
              .returns(ZIO.succeed(normalizeResult))
              .once(),
            s3ClientOrganizationMediaMock.uploadImageCatalogueItem
              .expects(organizationID, catalogueItemID, originalByteStream, normalizedByteStream)
              .returningZIO(uploadedImageResult)
              .once(),
            catalogueRepositoryMock.updateCatalogueItem
              .expects(
                organizationID,
                catalogueItemID,
                None,
                None,
                Some(catalogueItemImageAsset),
                None,
                Nil,
                Nil,
              )
              .returningZIO(())
              .once(),
          )

          val response = buildCatalogueFileService
            .uploadCatalogueItemImage(
              organizationID = organizationID,
              catalogueItemID = catalogueItemID,
              catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
              catalogueItemImageByteStream = catalogueItemImageByteStream,
            )
            .zioEither

          response shouldBe Right(())
          normalizeImageStreamCapture.value.value.runCollect.zioValue shouldBe Chunk.fromArray(scannedImageBytes)
        } finally Files.deleteIfExists(fileScannedPath.value)
      }

      "fail with an UnexpectedError before scanning when the catalogue item is missing" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        catalogueRepositoryMock.getCatalogueItemWithVariants
          .expects(organizationID, catalogueItemID)
          .returningZIO(None)
          .once()

        val serviceError = buildCatalogueFileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Catalogue item not found: catalogueItemID=[$catalogueItemID]"
        )
      }

      "fail with an UnexpectedError and stop the upload pipeline when scanning fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")
        val scanError = ServiceError.InternalServerError.UnexpectedError("Failed to scan catalogue item image")

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          isArchived = false,
        )
        val catalogueItemWithVariantsRow = CatalogueItemWithVariantsRow(
          catalogueItemRow,
          NonEmptyChunk.single(catalogueItemVariantRow),
        )

        inSequence(
          catalogueRepositoryMock.getCatalogueItemWithVariants
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemWithVariantsRow))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              catalogueItemImageOriginalFileName.value,
              SupportedMediaType.images,
              catalogueFileServiceConfig.uploadCatalogueItemImageFileBytesMax,
            )
            .returns(ZIO.fail(scanError))
            .once(),
        )

        val serviceError = buildCatalogueFileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe scanError
      }
    }

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
    val catalogueFileServiceConfig = CatalogueFileServiceConfig(
      uploadCatalogueItemImageFileBytesMax = 20L * 1024 * 1024,
      extractCatalogueItemsFileBytesMax = 10L * 1024 * 1024,
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
        ZLayer.succeed(catalogueFileServiceConfig),
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
