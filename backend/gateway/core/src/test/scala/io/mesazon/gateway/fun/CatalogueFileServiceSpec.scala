package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{AIClientDataExtraction, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.CatalogueFileServiceConfig
import io.mesazon.gateway.mock.Mocks
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{AIInstructions, CatalogueFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{CatalogueDomainArbitraries, ZWordSpecBase}
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import org.scalamock.matchers.ArgCapture.CaptureOne
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class CatalogueFileServiceSpec extends ZWordSpecBase, CatalogueDomainArbitraries, RepositoryArbitraries {

  "CatalogueFileService" when {
    "uploadImageItem" should {
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

          val response = buildCatalogueFileService()
            .uploadImageItem(
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

        val serviceError = buildCatalogueFileService()
          .uploadImageItem(
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

        val serviceError = buildCatalogueFileService()
          .uploadImageItem(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe scanError
      }

      "fail with an UnexpectedError when normalization fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.empty
        val catalogueItemRow                   = arbitrarySample[CatalogueItemRow].copy(
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
        val serviceError      = ServiceError.InternalServerError.UnexpectedError("Failed to normalize catalogue image")
        val scannedImageBytes = "scanned catalogue image".getBytes(StandardCharsets.UTF_8)
        val fileScannedPath   = FileScannedPath(Files.createTempFile("catalogue-normalize-failure-", ".jpeg"))
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
                  (fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(Files.size(fileScannedPath.value)))
                )
              )
              .once(),
            imageProcessingMock.normalize
              .expects(capture(normalizeImageStreamCapture), SupportedMediaType.images)
              .returns(ZIO.fail(serviceError))
              .once(),
          )
          buildCatalogueFileService()
            .uploadImageItem(
              organizationID,
              catalogueItemID,
              catalogueItemImageOriginalFileName,
              catalogueItemImageByteStream,
            )
            .zioError shouldBe serviceError
          normalizeImageStreamCapture.value.value.runCollect.zioValue shouldBe Chunk.fromArray(scannedImageBytes)
        } finally Files.deleteIfExists(fileScannedPath.value)
      }

      "fail with an UnexpectedError when storage upload fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.empty
        val catalogueItemRow                   = arbitrarySample[CatalogueItemRow].copy(
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
        val catalogueItemImageOriginalByteStream   = ImageOriginalByteStream(ZStream.empty)
        val catalogueItemImageNormalizedByteStream = ImageNormalizedByteStream(ZStream.empty)
        val normalizeResult: NormalizeResult       = (
          imageOriginalByteStream = catalogueItemImageOriginalByteStream,
          imageNormalizedByteStream = catalogueItemImageNormalizedByteStream,
        )
        val serviceError      = ServiceError.InternalServerError.UnexpectedError("Failed to upload catalogue image")
        val scannedImageBytes = "scanned catalogue image".getBytes(StandardCharsets.UTF_8)
        val fileScannedPath   = FileScannedPath(Files.createTempFile("catalogue-storage-failure-", ".jpeg"))
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
                  (fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(Files.size(fileScannedPath.value)))
                )
              )
              .once(),
            imageProcessingMock.normalize
              .expects(capture(normalizeImageStreamCapture), SupportedMediaType.images)
              .returns(ZIO.succeed(normalizeResult))
              .once(),
            s3ClientOrganizationMediaMock.uploadImageCatalogueItem
              .expects(
                organizationID,
                catalogueItemID,
                catalogueItemImageOriginalByteStream,
                catalogueItemImageNormalizedByteStream,
              )
              .returns(ZIO.fail(serviceError))
              .once(),
          )
          buildCatalogueFileService()
            .uploadImageItem(
              organizationID,
              catalogueItemID,
              catalogueItemImageOriginalFileName,
              catalogueItemImageByteStream,
            )
            .zioError shouldBe serviceError
          normalizeImageStreamCapture.value.value.runCollect.zioValue shouldBe Chunk.fromArray(scannedImageBytes)
        } finally Files.deleteIfExists(fileScannedPath.value)
      }
      "fail with an UnexpectedError when the image update fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        val serviceError = ServiceError.InternalServerError.UnexpectedError("Failed to update catalogue image")

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

        val scannedImageBytes = "scanned catalogue image".getBytes(StandardCharsets.UTF_8)
        val fileScannedPath   = FileScannedPath(Files.createTempFile("catalogue-repository-failure-", ".jpeg"))
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
              .returns(ZIO.fail(serviceError))
              .once(),
          )

          val response = buildCatalogueFileService()
            .uploadImageItem(
              organizationID = organizationID,
              catalogueItemID = catalogueItemID,
              catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
              catalogueItemImageByteStream = catalogueItemImageByteStream,
            )
            .zioError

          response shouldBe serviceError
          normalizeImageStreamCapture.value.value.runCollect.zioValue shouldBe Chunk.fromArray(scannedImageBytes)
        } finally Files.deleteIfExists(fileScannedPath.value)
      }

    }

    "aiExtract" should {
      "retain an incomplete item without repeating variant issues covered by its item issue" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("scanned-catalogue.jpeg"))
        val catalogueItemName              = arbitrarySample[CatalogueItemName]
        val catalogueItemDescription       = arbitrarySample[Option[CatalogueItemDescription]]
        val catalogueItemVariantAIExtract  = CatalogueItemVariantAIExtract(
          sku = Some(arbitrarySample[CatalogueItemVariantSku]),
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = None,
          missingRequiredSaleUnit = true,
          skuIsDuplicate = true,
          extractionNotes = List("SKU is partly obscured"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 7L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = catalogueItemName,
              description = catalogueItemDescription,
              variants = List(catalogueItemVariantAIExtract),
              atLeastOneVariant = true,
              nameIsDuplicate = true,
              extractionNotes = List("Item grouping is unclear"),
            )
          ),
          emptyEntryRows = List(2L, 4L),
          unidentifiedEntryRows = List(6L),
          extractionNotes = List("The source is cropped"),
        )
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )

        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemName,
              description = catalogueItemDescription,
              variants = Nil,
              needsAttention = true,
              extractionNotes = List(
                "At least one variant is required.",
                "Item name is duplicated in this extraction.",
                "Item grouping is unclear",
              ),
            )
          ),
          infoNotes = List(
            "Processed 1 of 7 entries",
            "Empty rows (2): [2, 4]",
            "Unidentified rows (1): [6]",
          ),
          extractionNotes = List("The source is cropped"),
        )
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions)
        )
        extractFromCsvCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "map usable variants to inserts and preserve general notes without requiring attention" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("usable-catalogue.jpeg"))
        val catalogueItemVariantInsert     = arbitrarySample[CatalogueItemVariantInsert]
        val catalogueItemName              = arbitrarySample[CatalogueItemName]
        val catalogueItemDescription       = arbitrarySample[Option[CatalogueItemDescription]]
        val attributeName                  = CatalogueItemVariantAttributeName("Color")
        val catalogueItemVariantAIExtract  = CatalogueItemVariantAIExtract(
          sku = catalogueItemVariantInsert.sku,
          label = catalogueItemVariantInsert.label,
          description = catalogueItemVariantInsert.description,
          attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
            CatalogueItemVariantAttributesAIExtract(name, value)
          } ++ List(
            CatalogueItemVariantAttributesAIExtract(attributeName, CatalogueItemVariantAttributeValue("Red")),
            CatalogueItemVariantAttributesAIExtract(attributeName, CatalogueItemVariantAttributeValue("Blue")),
          ),
          price = catalogueItemVariantInsert.price,
          sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
          missingRequiredSaleUnit = false,
          skuIsDuplicate = false,
          extractionNotes = List("Label is partly obscured"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          1L,
          1L,
          List(
            CatalogueItemAIExtract(
              name = catalogueItemName,
              description = catalogueItemDescription,
              variants = List(catalogueItemVariantAIExtract),
              atLeastOneVariant = false,
              nameIsDuplicate = false,
              extractionNotes = List("Source is blurry"),
            )
          ),
          Nil,
          Nil,
          Nil,
        )
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemName,
              description = catalogueItemDescription,
              variants = List(
                catalogueItemVariantInsert.copy(attributes =
                  catalogueItemVariantInsert.attributes
                    .updated(attributeName, CatalogueItemVariantAttributeValue("Blue"))
                )
              ),
              needsAttention = false,
              extractionNotes = List("Source is blurry", "Label is partly obscured"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "flag an item without variants" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("flagged-catalogue.jpeg"))
        val catalogueItemVariantInsert     =
          arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(arbitrarySample[CatalogueItemVariantSku]))
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = arbitrarySample[CatalogueItemName],
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = List(
            CatalogueItemVariantAIExtract(
              sku = catalogueItemVariantInsert.sku,
              label = catalogueItemVariantInsert.label,
              description = catalogueItemVariantInsert.description,
              attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
                CatalogueItemVariantAttributesAIExtract(name, value)
              },
              price = catalogueItemVariantInsert.price,
              sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
              missingRequiredSaleUnit = false,
              skuIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          atLeastOneVariant = false,
          nameIsDuplicate = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract =
          CatalogueFileAIExtract(
            1L,
            1L,
            List(catalogueItemAIExtract.copy(atLeastOneVariant = true, variants = Nil)),
            Nil,
            Nil,
            Nil,
          )
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemAIExtract.name,
              description = catalogueItemAIExtract.description,
              variants = Nil,
              needsAttention = true,
              extractionNotes = List("At least one variant is required."),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "flag a duplicate item name" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("flagged-catalogue.jpeg"))
        val catalogueItemVariantInsert     =
          arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(arbitrarySample[CatalogueItemVariantSku]))
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = arbitrarySample[CatalogueItemName],
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = List(
            CatalogueItemVariantAIExtract(
              sku = catalogueItemVariantInsert.sku,
              label = catalogueItemVariantInsert.label,
              description = catalogueItemVariantInsert.description,
              attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
                CatalogueItemVariantAttributesAIExtract(name, value)
              },
              price = catalogueItemVariantInsert.price,
              sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
              missingRequiredSaleUnit = false,
              skuIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          atLeastOneVariant = false,
          nameIsDuplicate = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract =
          CatalogueFileAIExtract(1L, 1L, List(catalogueItemAIExtract.copy(nameIsDuplicate = true)), Nil, Nil, Nil)
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemAIExtract.name,
              description = catalogueItemAIExtract.description,
              variants = List(catalogueItemVariantInsert),
              needsAttention = true,
              extractionNotes = List("Item name is duplicated in this extraction."),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "flag a missing selling unit" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("flagged-catalogue.jpeg"))
        val catalogueItemVariantInsert     =
          arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(arbitrarySample[CatalogueItemVariantSku]))
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = arbitrarySample[CatalogueItemName],
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = List(
            CatalogueItemVariantAIExtract(
              sku = catalogueItemVariantInsert.sku,
              label = catalogueItemVariantInsert.label,
              description = catalogueItemVariantInsert.description,
              attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
                CatalogueItemVariantAttributesAIExtract(name, value)
              },
              price = catalogueItemVariantInsert.price,
              sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
              missingRequiredSaleUnit = false,
              skuIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          atLeastOneVariant = false,
          nameIsDuplicate = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract =
          CatalogueFileAIExtract(
            1L,
            1L,
            List(
              catalogueItemAIExtract.copy(variants =
                catalogueItemAIExtract.variants.map(_.copy(sellingUnit = None, missingRequiredSaleUnit = true))
              )
            ),
            Nil,
            Nil,
            Nil,
          )
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemAIExtract.name,
              description = catalogueItemAIExtract.description,
              variants = Nil,
              needsAttention = true,
              extractionNotes = List("Selling unit is missing."),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "flag a duplicate SKU" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("flagged-catalogue.jpeg"))
        val catalogueItemVariantInsert     =
          arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(arbitrarySample[CatalogueItemVariantSku]))
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = arbitrarySample[CatalogueItemName],
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = List(
            CatalogueItemVariantAIExtract(
              sku = catalogueItemVariantInsert.sku,
              label = catalogueItemVariantInsert.label,
              description = catalogueItemVariantInsert.description,
              attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
                CatalogueItemVariantAttributesAIExtract(name, value)
              },
              price = catalogueItemVariantInsert.price,
              sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
              missingRequiredSaleUnit = false,
              skuIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          atLeastOneVariant = false,
          nameIsDuplicate = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract =
          CatalogueFileAIExtract(
            1L,
            1L,
            List(
              catalogueItemAIExtract.copy(variants = catalogueItemAIExtract.variants.map(_.copy(skuIsDuplicate = true)))
            ),
            Nil,
            Nil,
            Nil,
          )
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemAIExtract.name,
              description = catalogueItemAIExtract.description,
              variants = List(catalogueItemVariantInsert),
              needsAttention = true,
              extractionNotes = List("SKU is duplicated in this extraction."),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "flag a missing selling unit without an AI warning" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("flagged-catalogue.jpeg"))
        val catalogueItemVariantInsert     =
          arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(arbitrarySample[CatalogueItemVariantSku]))
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = arbitrarySample[CatalogueItemName],
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = List(
            CatalogueItemVariantAIExtract(
              sku = catalogueItemVariantInsert.sku,
              label = catalogueItemVariantInsert.label,
              description = catalogueItemVariantInsert.description,
              attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
                CatalogueItemVariantAttributesAIExtract(name, value)
              },
              price = catalogueItemVariantInsert.price,
              sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
              missingRequiredSaleUnit = false,
              skuIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          atLeastOneVariant = false,
          nameIsDuplicate = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract =
          CatalogueFileAIExtract(
            1L,
            1L,
            List(
              catalogueItemAIExtract.copy(variants = catalogueItemAIExtract.variants.map(_.copy(sellingUnit = None)))
            ),
            Nil,
            Nil,
            Nil,
          )
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = catalogueItemAIExtract.name,
              description = catalogueItemAIExtract.description,
              variants = Nil,
              needsAttention = true,
              extractionNotes = List("Selling unit is missing."),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "summarize an empty extraction" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("empty-catalogue.jpeg"))
        val extractionNotes                = List("No readable catalogue entries were found")
        val catalogueFileAIExtract         =
          CatalogueFileAIExtract(0L, 0L, Nil, Nil, Nil, extractionNotes)
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = extractionNotes,
        )
      }

      "report empty rows" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("empty-catalogue.jpeg"))
        val extractionNotes                = List("No readable catalogue entries were found")
        val catalogueFileAIExtract         =
          CatalogueFileAIExtract(0L, 0L, Nil, List(2L, 4L), Nil, extractionNotes)
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries", "Empty rows (2): [2, 4]"),
          extractionNotes = extractionNotes,
        )
      }

      "report unidentified rows" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("empty-catalogue.jpeg"))
        val extractionNotes                = List("No readable catalogue entries were found")
        val catalogueFileAIExtract         =
          CatalogueFileAIExtract(0L, 0L, Nil, Nil, List(6L), extractionNotes)
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries", "Unidentified rows (1): [6]"),
          extractionNotes = extractionNotes,
        )
      }

      "extract a photo without processing guidance" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] = None
        val organizationID                                                            = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName          = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream        = ZStream.empty
        val fileScannedPath                       = FileScannedPath(Path.of("guided-catalogue.jpeg"))
        val catalogueFileAIExtract                = CatalogueFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil)
        val extractFromImageProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
          extractFromImageProcessingMessagesRefOpt = Some(extractFromImageProcessingMessagesRef),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(Nil, List("Processed 0 of 0 entries"), Nil)
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions)
        )
        extractFromImageProcessingMessagesRef.refValue shouldBe List(catalogueFileProcessingMessageOpt.map(_.value))
        extractFromCsvCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "forward photo processing guidance" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] =
          Some(CatalogueFileProcessingMessage("Omit prices from variants"))
        val organizationID                        = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName          = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream        = ZStream.empty
        val fileScannedPath                       = FileScannedPath(Path.of("guided-catalogue.jpeg"))
        val catalogueFileAIExtract                = CatalogueFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil)
        val extractFromImageProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(catalogueFileAIExtract)),
          extractFromImageProcessingMessagesRefOpt = Some(extractFromImageProcessingMessagesRef),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(Nil, List("Processed 0 of 0 entries"), Nil)
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions)
        )
        extractFromImageProcessingMessagesRef.refValue shouldBe List(catalogueFileProcessingMessageOpt.map(_.value))
        extractFromCsvCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "extract catalogue items from CSV" in new TestContext {
        val catalogueFileProcessingMessage      = CatalogueFileProcessingMessage("Omit prices from variants")
        val extractFromCsvProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue
        val organizationID                      = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName        = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream      = ZStream.empty
        val fileScannedPath                     = FileScannedPath(Path.of("catalogue.csv"))
        val csvRecords             = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemName      = CatalogueItemName("Item")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          1L,
          1L,
          List(
            CatalogueItemAIExtract(
              catalogueItemName,
              None,
              Nil,
              atLeastOneVariant = true,
              nameIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          Nil,
          Nil,
          Nil,
        )
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(ZStream.fromIterable(csvRecords)).once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(catalogueFileAIExtract))),
          extractFromCsvProcessingMessagesRefOpt = Some(extractFromCsvProcessingMessagesRef),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            Some(catalogueFileProcessingMessage),
            catalogueFileExtractByteStream,
          )
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          List(
            CatalogueItemCandidate(
              catalogueItemName,
              None,
              Nil,
              needsAttention = true,
              extractionNotes = List("At least one variant is required."),
            )
          ),
          List("Processed 1 of 1 entries"),
          Nil,
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCatalogueItemsFromFileInstructions)
        )
        extractFromCsvProcessingMessagesRef.refValue shouldBe List(Some(catalogueFileProcessingMessage.value))
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "extract catalogue items from XLS" in new TestContext {
        val extractFromCsvProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue
        val organizationID                      = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName        = CatalogueFileExtractFileName("catalogue.xls")
        val catalogueFileExtractByteStream      = ZStream.empty
        val fileScannedPath                     = FileScannedPath(Path.of("catalogue.xls"))
        val csvRecords             = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemName      = CatalogueItemName("Item")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          1L,
          1L,
          List(
            CatalogueItemAIExtract(
              catalogueItemName,
              None,
              Nil,
              atLeastOneVariant = true,
              nameIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          Nil,
          Nil,
          Nil,
        )
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.XLS, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertExcelToCsv
            .expects(fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(catalogueFileAIExtract))),
          extractFromCsvProcessingMessagesRefOpt = Some(extractFromCsvProcessingMessagesRef),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          List(
            CatalogueItemCandidate(
              catalogueItemName,
              None,
              Nil,
              needsAttention = true,
              extractionNotes = List("At least one variant is required."),
            )
          ),
          List("Processed 1 of 1 entries"),
          Nil,
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCatalogueItemsFromFileInstructions)
        )
        extractFromCsvProcessingMessagesRef.refValue shouldBe List(None)
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "extract catalogue items from XLSX" in new TestContext {
        val extractFromCsvProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue
        val organizationID                      = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName        = CatalogueFileExtractFileName("catalogue.xlsx")
        val catalogueFileExtractByteStream      = ZStream.empty
        val fileScannedPath                     = FileScannedPath(Path.of("catalogue.xlsx"))
        val csvRecords             = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemName      = CatalogueItemName("Item")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          1L,
          1L,
          List(
            CatalogueItemAIExtract(
              catalogueItemName,
              None,
              Nil,
              atLeastOneVariant = true,
              nameIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          Nil,
          Nil,
          Nil,
        )
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.XLSX, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertExcelToCsv
            .expects(fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(catalogueFileAIExtract))),
          extractFromCsvProcessingMessagesRefOpt = Some(extractFromCsvProcessingMessagesRef),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          List(
            CatalogueItemCandidate(
              catalogueItemName,
              None,
              Nil,
              needsAttention = true,
              extractionNotes = List("At least one variant is required."),
            )
          ),
          List("Processed 1 of 1 entries"),
          Nil,
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCatalogueItemsFromFileInstructions)
        )
        extractFromCsvProcessingMessagesRef.refValue shouldBe List(None)
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "preserve spreadsheet batch order and combine counts and row findings" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("batched-catalogue.csv"))
        val csvRecords = CSVParser.parse("Name\nItem A\nItem B", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemNameFirst      = CatalogueItemName("Item A")
        val catalogueItemNameSecond     = CatalogueItemName("Item B")
        val catalogueFileAIExtractFirst = CatalogueFileAIExtract(
          2L,
          1L,
          List(
            CatalogueItemAIExtract(
              catalogueItemNameFirst,
              None,
              Nil,
              atLeastOneVariant = true,
              nameIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          List(3L),
          List(4L),
          List("First batch is cropped"),
        )
        val catalogueFileAIExtractSecond = CatalogueFileAIExtract(
          2L,
          1L,
          List(
            CatalogueItemAIExtract(
              catalogueItemNameSecond,
              None,
              Nil,
              atLeastOneVariant = true,
              nameIsDuplicate = false,
              extractionNotes = Nil,
            )
          ),
          List(6L),
          List(7L),
          List("Second batch is blurry"),
        )
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(ZStream.fromIterable(csvRecords)).once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(catalogueFileAIExtractFirst, catalogueFileAIExtractSecond))),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        response shouldBe CatalogueFileAiExtractPostResponse(
          List(
            CatalogueItemCandidate(
              catalogueItemNameFirst,
              None,
              Nil,
              needsAttention = true,
              extractionNotes = List("At least one variant is required."),
            ),
            CatalogueItemCandidate(
              catalogueItemNameSecond,
              None,
              Nil,
              needsAttention = true,
              extractionNotes = List("At least one variant is required."),
            ),
          ),
          List("Processed 2 of 4 entries", "Empty rows (2): [3, 6]", "Unidentified rows (2): [4, 7]"),
          List("First batch is cropped", "Second batch is blurry"),
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCatalogueItemsFromFileInstructions)
        )
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "mark duplicate names and SKUs ignoring case" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("duplicates-catalogue.csv"))
        val csvRecords                     = CSVParser
          .parse(
            "Name,SKU\nBolt,SKU-A\nNut,SKU-C\nbOLT,SKU-B\nWasher,sku-a",
            CSVFormat.DEFAULT,
          )
          .getRecords
          .asScala
          .toList
        val catalogueItemVariantInsert = arbitrarySample[CatalogueItemVariantInsert].copy(
          sku = Some(CatalogueItemVariantSku("SKU-A"))
        )
        val catalogueItemVariantInsertWithoutSku = catalogueItemVariantInsert.copy(sku = None)
        val catalogueItemVariantInsertUnique     =
          catalogueItemVariantInsert.copy(sku = Some(CatalogueItemVariantSku("SKU-C")))
        val catalogueItemVariantInsertNameDuplicate =
          catalogueItemVariantInsert.copy(sku = Some(CatalogueItemVariantSku("SKU-B")))
        val catalogueItemVariantInsertSkuDuplicate =
          catalogueItemVariantInsert.copy(sku = Some(CatalogueItemVariantSku("sku-a")))
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = catalogueItemVariantInsert.sku,
          label = catalogueItemVariantInsert.label,
          description = catalogueItemVariantInsert.description,
          attributes = catalogueItemVariantInsert.attributes.toList.map { case (name, value) =>
            CatalogueItemVariantAttributesAIExtract(name, value)
          },
          price = catalogueItemVariantInsert.price,
          sellingUnit = Some(catalogueItemVariantInsert.sellingUnit),
          missingRequiredSaleUnit = false,
          skuIsDuplicate = true,
          extractionNotes = Nil,
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = CatalogueItemName("Bolt"),
          description = None,
          variants = List(catalogueItemVariantAIExtract, catalogueItemVariantAIExtract.copy(sku = None)),
          atLeastOneVariant = false,
          nameIsDuplicate = true,
          extractionNotes = Nil,
        )
        val catalogueItemAIExtractUnique = catalogueItemAIExtract.copy(
          name = CatalogueItemName("Nut"),
          variants = List(catalogueItemVariantAIExtract.copy(sku = catalogueItemVariantInsertUnique.sku)),
        )
        val catalogueItemAIExtractNameDuplicate = catalogueItemAIExtract.copy(
          name = CatalogueItemName("bOLT"),
          variants = List(
            catalogueItemVariantAIExtract.copy(sku = catalogueItemVariantInsertNameDuplicate.sku),
            catalogueItemVariantAIExtract.copy(sku = None),
          ),
        )
        val catalogueItemAIExtractSkuDuplicate = catalogueItemAIExtract.copy(
          name = CatalogueItemName("Washer"),
          variants = List(catalogueItemVariantAIExtract.copy(sku = catalogueItemVariantInsertSkuDuplicate.sku)),
        )
        val catalogueFileAIExtractFirst = CatalogueFileAIExtract(
          2L,
          2L,
          List(catalogueItemAIExtract, catalogueItemAIExtractUnique),
          Nil,
          Nil,
          Nil,
        )
        val catalogueFileAIExtractSecond = catalogueFileAIExtractFirst.copy(
          candidates = List(catalogueItemAIExtractNameDuplicate, catalogueItemAIExtractSkuDuplicate)
        )
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(ZStream.fromIterable(csvRecords)).once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(catalogueFileAIExtractFirst, catalogueFileAIExtractSecond))),
        )
        val response = buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue
        val catalogueItemCandidateExpected = CatalogueItemCandidate(
          name = catalogueItemAIExtract.name,
          description = None,
          variants = List(catalogueItemVariantInsert, catalogueItemVariantInsertWithoutSku),
          needsAttention = true,
          extractionNotes =
            List("Item name is duplicated in this extraction.", "Variant 1: SKU is duplicated in this extraction."),
        )
        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            catalogueItemCandidateExpected,
            catalogueItemCandidateExpected.copy(
              name = catalogueItemAIExtractUnique.name,
              variants = List(catalogueItemVariantInsertUnique),
              needsAttention = false,
              extractionNotes = Nil,
            ),
            catalogueItemCandidateExpected.copy(
              name = catalogueItemAIExtractNameDuplicate.name,
              variants = List(catalogueItemVariantInsertNameDuplicate, catalogueItemVariantInsertWithoutSku),
              extractionNotes = List("Item name is duplicated in this extraction."),
            ),
            catalogueItemCandidateExpected.copy(
              name = catalogueItemAIExtractSkuDuplicate.name,
              variants = List(catalogueItemVariantInsertSkuDuplicate),
              extractionNotes = List("SKU is duplicated in this extraction."),
            ),
          ),
          infoNotes = List("Processed 4 of 4 entries"),
          extractionNotes = Nil,
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCatalogueItemsFromFileInstructions)
        )
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "fail with an UnexpectedError when extraction file scanning fails" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val serviceError = ServiceError.InternalServerError.UnexpectedError("Unable to scan catalogue")

        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.fail(serviceError))
          .once()

        buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
      }

      "fail with an UnexpectedError when photo AI extraction fails" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("failed-catalogue.jpeg"))
        val serviceError = ServiceError.InternalServerError.UnexpectedError("Unable to extract catalogue")

        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.extractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
          .once()
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[CatalogueFileAIExtract](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.fail(serviceError)),
        )

        buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions)
        )
        extractFromCsvCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }
      "fail with an UnexpectedError when spreadsheet conversion fails" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("failed-catalogue.csv"))
        val serviceError    = ServiceError.InternalServerError.UnexpectedError("Failed spreadsheet conversion")
        val csvRecordStream = ZStream.fail(serviceError)
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(csvRecordStream).once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[CatalogueFileAIExtract](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.fail(serviceError)),
        )
        buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
        extractFromCsvCallsRef.refValue shouldBe Nil
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "fail with an UnexpectedError when spreadsheet AI extraction fails" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("failed-catalogue.csv"))
        val csvRecords                     = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val serviceError    = ServiceError.InternalServerError.UnexpectedError("Failed spreadsheet AI extraction")
        val csvRecordStream = ZStream.fromIterable(csvRecords)
        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.extractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(csvRecordStream).once(),
        )
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[CatalogueFileAIExtract](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.fail(serviceError)),
        )
        buildCatalogueFileService(aiClientDataExtraction)
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCatalogueItemsFromFileInstructions)
        )
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
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

    val extractFromImageCallsRef = Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
    val extractFromCsvCallsRef   = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
    val noteCompactionCallsRef   =
      Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue

    def buildCatalogueFileService(
        aiClientDataExtraction: AIClientDataExtraction = aiClientDataExtractionMock
    ): CatalogueFileService[ServiceTask] = ZIO
      .service[CatalogueFileService[ServiceTask]]
      .provide(
        CatalogueFileService.local,
        ZLayer.succeed(catalogueFileServiceConfig),
        ZLayer.succeed(catalogueRepositoryMock),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(spreadsheetToolMock),
        ZLayer.succeed(s3ClientOrganizationMediaMock),
        ZLayer.succeed(aiClientDataExtraction),
      )
      .zioValue
  }
}
