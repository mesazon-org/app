package io.mesazon.gateway.fun

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, JsonValueCodec}
import io.circe.{parser, Json}
import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{AIClientDataExtraction, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.CatalogueFileServiceConfig
import io.mesazon.gateway.json.tapir.{
  catalogueFileAiExtractPostResponseCodec,
  catalogueItemCandidateCodec,
  catalogueItemCandidateSchema,
}
import io.mesazon.gateway.json.{ai, OpenAIJsonSchema}
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{AIInstructions, CatalogueFileService, ServiceTask}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{CatalogueDomainArbitraries, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import org.scalamock.matchers.ArgCapture.CaptureOne
import smithy4s.Blob
import smithy4s.json.Json as SmithyJson
import sttp.tapir.{Schema, SchemaType}
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale
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
      "map every variant to a candidate and keep AI notes, item then variants, unprefixed, without attention" in new TestContext {
        val catalogueItemName               = arbitrarySample[CatalogueItemName]
        val catalogueItemDescription        = arbitrarySample[Option[CatalogueItemDescription]]
        val catalogueItemVariantSku         = arbitrarySample[Option[CatalogueItemVariantSku]]
        val catalogueItemVariantLabel       = arbitrarySample[Option[CatalogueItemVariantLabel]]
        val catalogueItemVariantDescription = arbitrarySample[Option[CatalogueItemVariantDescription]]
        val catalogueItemVariantAttributes  =
          arbitrarySample[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]]
        val catalogueItemVariantSaleUnit      = arbitrarySample[CatalogueItemVariantSaleUnit]
        val price                             = arbitrarySample[Price]
        val catalogueItemVariantAttributeName = CatalogueItemVariantAttributeName("Color")
        val catalogueItemVariantAIExtract     = CatalogueItemVariantAIExtract(
          sku = catalogueItemVariantSku,
          label = catalogueItemVariantLabel,
          description = catalogueItemVariantDescription,
          attributes = catalogueItemVariantAttributes.toList.map { case (name, value) =>
            CatalogueItemVariantAttributesAIExtract(name, value)
          } ++ List(
            CatalogueItemVariantAttributesAIExtract(
              catalogueItemVariantAttributeName,
              CatalogueItemVariantAttributeValue("Red"),
            ),
            CatalogueItemVariantAttributesAIExtract(
              catalogueItemVariantAttributeName,
              CatalogueItemVariantAttributeValue("Blue"),
            ),
          ),
          price = Some(
            CatalogueItemVariantPriceAIExtract(
              amount = price.amount,
              currency = Some(price.currency),
              currencyInferred = false,
            )
          ),
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = List("Label is partly obscured"),
        )
        val catalogueItemVariantAIExtractSecond = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = List("Size is hard to read"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = catalogueItemDescription,
              variants = List(catalogueItemVariantAIExtract, catalogueItemVariantAIExtractSecond),
              extractionNotes = List("Source is blurry"),
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = catalogueItemDescription,
              variants = List(
                CatalogueItemVariantCandidate(
                  sku = catalogueItemVariantSku,
                  label = catalogueItemVariantLabel,
                  description = catalogueItemVariantDescription,
                  attributes = catalogueItemVariantAttributes
                    .updated(catalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue("Blue")),
                  price =
                    Some(CatalogueItemVariantPriceCandidate(amount = price.amount, currency = Some(price.currency))),
                  sellingUnit = Some(catalogueItemVariantSaleUnit),
                ),
                CatalogueItemVariantCandidate(
                  sku = None,
                  label = None,
                  description = None,
                  attributes = Map.empty,
                  price = None,
                  sellingUnit = Some(catalogueItemVariantSaleUnit),
                ),
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Source is blurry", "Label is partly obscured", "Size is hard to read"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries", "Item variants created 2"),
          extractionNotes = Nil,
        )
      }

      "report /variants as missing when an item has no variants" in new TestContext {
        val catalogueItemName        = arbitrarySample[CatalogueItemName]
        val catalogueItemDescription = arbitrarySample[Option[CatalogueItemDescription]]
        val catalogueFileAIExtract   = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = catalogueItemDescription,
              variants = Nil,
              extractionNotes = List("Item grouping is unclear"),
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = List("The source is cropped"),
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = catalogueItemDescription,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = List("Item grouping is unclear"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List("The source is cropped"),
        )
      }

      "report /name as duplicate for exactly equal names in an image" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val catalogueItemNameUnique       = CatalogueItemName.assume(catalogueItemName.value + " Unique")
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = Some(catalogueItemName),
          description = None,
          variants = List(catalogueItemVariantAIExtract),
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueItemCandidateDuplicate = CatalogueItemCandidate(
          name = Some(catalogueItemName),
          description = None,
          variants = List(catalogueItemVariantCandidate),
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo("/name")),
          needsAttention = true,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          candidates = List(
            catalogueItemAIExtract,
            catalogueItemAIExtract.copy(name = Some(catalogueItemNameUnique)),
            catalogueItemAIExtract,
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        catalogueItemNameUnique shouldNot equal(catalogueItemName)

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            catalogueItemCandidateDuplicate,
            catalogueItemCandidateDuplicate.copy(
              name = Some(catalogueItemNameUnique),
              duplicateFields = Nil,
              needsAttention = false,
            ),
            catalogueItemCandidateDuplicate,
          ),
          infoNotes = List("Processed 3 of 3 entries"),
          extractionNotes = Nil,
        )
      }

      "not report differently cased names as duplicates" in new TestContext {
        val catalogueItemName             = CatalogueItemName("Bolt")
        val catalogueItemNameCased        = CatalogueItemName("bOLT")
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = Some(catalogueItemName),
          description = None,
          variants = List(catalogueItemVariantAIExtract),
          extractionNotes = Nil,
        )
        val catalogueItemCandidate = CatalogueItemCandidate(
          name = Some(catalogueItemName),
          description = None,
          variants = List(
            CatalogueItemVariantCandidate(
              sku = None,
              label = None,
              description = None,
              attributes = Map.empty,
              price = None,
              sellingUnit = Some(catalogueItemVariantSaleUnit),
            )
          ),
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(catalogueItemAIExtract, catalogueItemAIExtract.copy(name = Some(catalogueItemNameCased))),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        catalogueItemNameCased shouldNot equal(catalogueItemName)
        catalogueItemNameCased.value.toLowerCase(Locale.ROOT) shouldBe catalogueItemName.value.toLowerCase(Locale.ROOT)

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(catalogueItemCandidate, catalogueItemCandidate.copy(name = Some(catalogueItemNameCased))),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "report /name as missing for nameless candidates without reporting them as duplicates of each other" in new TestContext {
        val catalogueItemVariantSku       = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemVariantSkuOther  = CatalogueItemVariantSku.assume(catalogueItemVariantSku.value + "-2")
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = Some(catalogueItemVariantSku),
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemAIExtractNameless = CatalogueItemAIExtract(
          name = None,
          description = None,
          variants = List(catalogueItemVariantAIExtract),
          extractionNotes = List("No item name is shown"),
        )
        val catalogueItemCandidateNameless = CatalogueItemCandidate(
          name = None,
          description = None,
          variants = List(
            CatalogueItemVariantCandidate(
              sku = Some(catalogueItemVariantSku),
              label = None,
              description = None,
              attributes = Map.empty,
              price = None,
              sellingUnit = Some(catalogueItemVariantSaleUnit),
            )
          ),
          inferredFields = Nil,
          missingFields = List(FieldInfo("/name")),
          duplicateFields = Nil,
          needsAttention = true,
          extractionNotes = List("No item name is shown"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(
            catalogueItemAIExtractNameless,
            catalogueItemAIExtractNameless.copy(variants =
              List(catalogueItemVariantAIExtract.copy(sku = Some(catalogueItemVariantSkuOther)))
            ),
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        catalogueItemVariantSkuOther.value.toLowerCase(Locale.ROOT) shouldNot equal(
          catalogueItemVariantSku.value.toLowerCase(Locale.ROOT)
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            catalogueItemCandidateNameless,
            catalogueItemCandidateNameless.copy(variants =
              catalogueItemCandidateNameless.variants.map(_.copy(sku = Some(catalogueItemVariantSkuOther)))
            ),
          ),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "keep a variant without a selling unit and report /variants/{i}/sellingUnit as missing" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = None,
          sellingUnitInferred = false,
          extractionNotes = List("The selling unit is not shown"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                CatalogueItemVariantCandidate(
                  sku = None,
                  label = None,
                  description = None,
                  attributes = Map.empty,
                  price = None,
                  sellingUnit = None,
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants/0/sellingUnit")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = List("The selling unit is not shown"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report an absent selling unit as missing, not inferred, when sellingUnitInferred is true" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = None,
          sellingUnitInferred = true,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                CatalogueItemVariantCandidate(
                  sku = None,
                  label = None,
                  description = None,
                  attributes = Map.empty,
                  price = None,
                  sellingUnit = None,
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants/0/sellingUnit")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report /variants/{i}/sku as duplicate for SKUs equal ignoring case in an image, including two variants of the same candidate" in new TestContext {
        val catalogueItemName             = CatalogueItemName("Bolt")
        val catalogueItemNameOther        = CatalogueItemName("Nut")
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("AB-1"))),
                catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("ab-1"))),
              ),
              extractionNotes = Nil,
            ),
            CatalogueItemAIExtract(
              name = Some(catalogueItemNameOther),
              description = None,
              variants = List(
                catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("CD-2"))),
                catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("aB-1"))),
              ),
              extractionNotes = Nil,
            ),
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("AB-1"))),
                catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("ab-1"))),
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = List(FieldInfo("/variants/0/sku"), FieldInfo("/variants/1/sku")),
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CatalogueItemCandidate(
              name = Some(catalogueItemNameOther),
              description = None,
              variants = List(
                catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("CD-2"))),
                catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("aB-1"))),
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = List(FieldInfo("/variants/1/sku")),
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 2 of 2 entries", "Item variants created 4"),
          extractionNotes = Nil,
        )
      }

      "never report absent SKUs as duplicates" in new TestContext {
        val catalogueItemName             = CatalogueItemName("Bolt")
        val catalogueItemNameOther        = CatalogueItemName("Nut")
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantAIExtract, catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            ),
            CatalogueItemAIExtract(
              name = Some(catalogueItemNameOther),
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            ),
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantCandidate, catalogueItemVariantCandidate),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
            CatalogueItemCandidate(
              name = Some(catalogueItemNameOther),
              description = None,
              variants = List(catalogueItemVariantCandidate),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 2 of 2 entries", "Item variants created 3"),
          extractionNotes = Nil,
        )
      }

      "keep a price without a currency and report /variants/{i}/price/currency as missing, with no price path for an absent price" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val priceAmount                   = arbitrarySample[Price].amount
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                catalogueItemVariantAIExtract,
                catalogueItemVariantAIExtract.copy(price =
                  Some(
                    CatalogueItemVariantPriceAIExtract(amount = priceAmount, currency = None, currencyInferred = false)
                  )
                ),
              ),
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                catalogueItemVariantCandidate,
                catalogueItemVariantCandidate.copy(price =
                  Some(CatalogueItemVariantPriceCandidate(amount = priceAmount, currency = None))
                ),
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants/1/price/currency")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries", "Item variants created 2"),
          extractionNotes = Nil,
        )
      }

      "report an inferred selling unit and an inferred currency as inferred fields without attention" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val price                         = arbitrarySample[Price]
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = Some(
            CatalogueItemVariantPriceAIExtract(
              amount = price.amount,
              currency = Some(price.currency),
              currencyInferred = true,
            )
          ),
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = true,
          extractionNotes = List("Unit and currency come from the header"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                CatalogueItemVariantCandidate(
                  sku = None,
                  label = None,
                  description = None,
                  attributes = Map.empty,
                  price =
                    Some(CatalogueItemVariantPriceCandidate(amount = price.amount, currency = Some(price.currency))),
                  sellingUnit = Some(catalogueItemVariantSaleUnit),
                )
              ),
              inferredFields = List(FieldInfo("/variants/0/sellingUnit"), FieldInfo("/variants/0/price/currency")),
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Unit and currency come from the header"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report an absent currency as missing, not inferred, when currencyInferred is true" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val priceAmount                   = arbitrarySample[Price].amount
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price =
            Some(CatalogueItemVariantPriceAIExtract(amount = priceAmount, currency = None, currencyInferred = true)),
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                CatalogueItemVariantCandidate(
                  sku = None,
                  label = None,
                  description = None,
                  attributes = Map.empty,
                  price = Some(CatalogueItemVariantPriceCandidate(amount = priceAmount, currency = None)),
                  sellingUnit = Some(catalogueItemVariantSaleUnit),
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants/0/price/currency")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report a later variant's findings at that variant's position, after the item's own paths" in new TestContext {
        val catalogueItemName             = CatalogueItemName("Bolt")
        val priceAmount                   = arbitrarySample[Price].amount
        val currency                      = arbitrarySample[Currency]
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = Some(catalogueItemName),
          description = None,
          variants = List(
            catalogueItemVariantAIExtract,
            catalogueItemVariantAIExtract.copy(
              sku = Some(CatalogueItemVariantSku("AB-1")),
              price = Some(
                CatalogueItemVariantPriceAIExtract(amount = priceAmount, currency = None, currencyInferred = false)
              ),
              sellingUnit = None,
            ),
            catalogueItemVariantAIExtract.copy(
              sku = Some(CatalogueItemVariantSku("ab-1")),
              price = Some(
                CatalogueItemVariantPriceAIExtract(
                  amount = priceAmount,
                  currency = Some(currency),
                  currencyInferred = true,
                )
              ),
              sellingUnitInferred = true,
            ),
          ),
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(catalogueItemAIExtract, catalogueItemAIExtract.copy(variants = Nil)),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                catalogueItemVariantCandidate,
                catalogueItemVariantCandidate.copy(
                  sku = Some(CatalogueItemVariantSku("AB-1")),
                  price = Some(CatalogueItemVariantPriceCandidate(amount = priceAmount, currency = None)),
                  sellingUnit = None,
                ),
                catalogueItemVariantCandidate.copy(
                  sku = Some(CatalogueItemVariantSku("ab-1")),
                  price = Some(CatalogueItemVariantPriceCandidate(amount = priceAmount, currency = Some(currency))),
                ),
              ),
              inferredFields = List(FieldInfo("/variants/2/sellingUnit"), FieldInfo("/variants/2/price/currency")),
              missingFields = List(FieldInfo("/variants/1/sellingUnit"), FieldInfo("/variants/1/price/currency")),
              duplicateFields = List(FieldInfo("/name"), FieldInfo("/variants/1/sku"), FieldInfo("/variants/2/sku")),
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = List(FieldInfo("/name")),
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 2 of 2 entries", "Item variants created 4"),
          extractionNotes = Nil,
        )
      }

      "mark a candidate as needing attention for each single missing or duplicate field" in new TestContext {
        val priceAmount                   = arbitrarySample[Price].amount
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = None,
          description = None,
          variants = List(catalogueItemVariantAIExtract),
          extractionNotes = Nil,
        )
        val catalogueItemCandidate = CatalogueItemCandidate(
          name = None,
          description = None,
          variants = List(catalogueItemVariantCandidate),
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = true,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 8L,
          entriesProcessed = 8L,
          candidates = List(
            catalogueItemAIExtract,
            catalogueItemAIExtract.copy(name = Some(CatalogueItemName("Bolt")), variants = Nil),
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("Nut")),
              variants = List(catalogueItemVariantAIExtract.copy(sellingUnit = None)),
            ),
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("Washer")),
              variants = List(
                catalogueItemVariantAIExtract.copy(price =
                  Some(
                    CatalogueItemVariantPriceAIExtract(amount = priceAmount, currency = None, currencyInferred = false)
                  )
                )
              ),
            ),
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("Screw")),
              variants = List(catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("AB-1")))),
            ),
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("Rivet")),
              variants = List(catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("ab-1")))),
            ),
            catalogueItemAIExtract.copy(name = Some(CatalogueItemName("Pin"))),
            catalogueItemAIExtract.copy(name = Some(CatalogueItemName("Pin"))),
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            catalogueItemCandidate.copy(missingFields = List(FieldInfo("/name"))),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Bolt")),
              variants = Nil,
              missingFields = List(FieldInfo("/variants")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Nut")),
              variants = List(catalogueItemVariantCandidate.copy(sellingUnit = None)),
              missingFields = List(FieldInfo("/variants/0/sellingUnit")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Washer")),
              variants = List(
                catalogueItemVariantCandidate.copy(price =
                  Some(CatalogueItemVariantPriceCandidate(amount = priceAmount, currency = None))
                )
              ),
              missingFields = List(FieldInfo("/variants/0/price/currency")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Screw")),
              variants = List(catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("AB-1")))),
              duplicateFields = List(FieldInfo("/variants/0/sku")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Rivet")),
              variants = List(catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("ab-1")))),
              duplicateFields = List(FieldInfo("/variants/0/sku")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Pin")),
              duplicateFields = List(FieldInfo("/name")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Pin")),
              duplicateFields = List(FieldInfo("/name")),
            ),
          ),
          infoNotes = List("Processed 8 of 8 entries"),
          extractionNotes = Nil,
        )
      }

      "not mark a candidate whose only findings are inferred fields or AI notes" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val price                         = arbitrarySample[Price]
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = Some(
            CatalogueItemVariantPriceAIExtract(
              amount = price.amount,
              currency = Some(price.currency),
              currencyInferred = true,
            )
          ),
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = List("Currency comes from the supplier"),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = List("Description is faint"),
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = List("The image is slightly cropped"),
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = List(
                CatalogueItemVariantCandidate(
                  sku = None,
                  label = None,
                  description = None,
                  attributes = Map.empty,
                  price =
                    Some(CatalogueItemVariantPriceCandidate(amount = price.amount, currency = Some(price.currency))),
                  sellingUnit = Some(catalogueItemVariantSaleUnit),
                )
              ),
              inferredFields = List(FieldInfo("/variants/0/price/currency")),
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Description is faint", "Currency comes from the supplier"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List("The image is slightly cropped"),
        )
      }

      "report exact name duplicates and case-insensitive SKU duplicates across spreadsheet groups" in new TestContext {
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = Some(CatalogueItemName("Bolt")),
          description = None,
          variants = List(
            catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("AB-1"))),
            catalogueItemVariantAIExtract,
          ),
          extractionNotes = Nil,
        )
        val catalogueItemCandidate = CatalogueItemCandidate(
          name = Some(CatalogueItemName("Bolt")),
          description = None,
          variants = List(
            catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("AB-1"))),
            catalogueItemVariantCandidate,
          ),
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo("/name"), FieldInfo("/variants/0/sku")),
          needsAttention = true,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtractFirst = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          candidates = List(
            catalogueItemAIExtract,
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("Nut")),
              variants = List(catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("CD-2")))),
            ),
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("bOLT")),
              variants = List(catalogueItemVariantAIExtract),
            ),
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtractSecond = catalogueFileAIExtractFirst.copy(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(
            catalogueItemAIExtract.copy(variants =
              List(
                catalogueItemVariantAIExtract,
                catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("ab-1"))),
              )
            ),
            catalogueItemAIExtract.copy(
              name = Some(CatalogueItemName("Washer")),
              variants = List(catalogueItemVariantAIExtract.copy(sku = Some(CatalogueItemVariantSku("Ab-1")))),
            ),
          ),
        )

        aiExtractFromSpreadsheet(
          NonEmptyChunk(catalogueFileAIExtractFirst, catalogueFileAIExtractSecond)
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            catalogueItemCandidate,
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Nut")),
              variants = List(catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("CD-2")))),
              duplicateFields = Nil,
              needsAttention = false,
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("bOLT")),
              variants = List(catalogueItemVariantCandidate),
              duplicateFields = Nil,
              needsAttention = false,
            ),
            catalogueItemCandidate.copy(
              variants = List(
                catalogueItemVariantCandidate,
                catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("ab-1"))),
              ),
              duplicateFields = List(FieldInfo("/name"), FieldInfo("/variants/1/sku")),
            ),
            catalogueItemCandidate.copy(
              name = Some(CatalogueItemName("Washer")),
              variants = List(catalogueItemVariantCandidate.copy(sku = Some(CatalogueItemVariantSku("Ab-1")))),
              duplicateFields = List(FieldInfo("/variants/0/sku")),
            ),
          ),
          infoNotes = List("Processed 5 of 5 entries", "Item variants created 7"),
          extractionNotes = Nil,
        )
      }

      "summarize an empty extraction" in new TestContext {
        val extractionNotes        = List("No readable catalogue entries were found")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = extractionNotes,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = extractionNotes,
        )
      }

      "report empty rows" in new TestContext {
        val extractionNotes        = List("No readable catalogue entries were found")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = List(2L, 4L),
          unidentifiedEntryRows = Nil,
          extractionNotes = extractionNotes,
        )

        aiExtractFromSpreadsheet(
          NonEmptyChunk.single(catalogueFileAIExtract)
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 2 entries", "Empty rows (2) at [2, 4]"),
          extractionNotes = extractionNotes,
        )
      }

      "report unidentified rows" in new TestContext {
        val extractionNotes        = List("No readable catalogue entries were found")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = List(6L),
          extractionNotes = extractionNotes,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 1 entries", "Unidentified rows (1) at [6]"),
          extractionNotes = extractionNotes,
        )
      }

      "report the catalogue items created when there are more items than processed entries, without a variants line when the variants equal the items" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val catalogueItemNameOther        = CatalogueItemName.assume(catalogueItemName.value + " Other")
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemAIExtract = CatalogueItemAIExtract(
          name = Some(catalogueItemName),
          description = None,
          variants = List(catalogueItemVariantAIExtract),
          extractionNotes = Nil,
        )
        val catalogueItemCandidate = CatalogueItemCandidate(
          name = Some(catalogueItemName),
          description = None,
          variants = List(
            CatalogueItemVariantCandidate(
              sku = None,
              label = None,
              description = None,
              attributes = Map.empty,
              price = None,
              sellingUnit = Some(catalogueItemVariantSaleUnit),
            )
          ),
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 1L,
          candidates = List(catalogueItemAIExtract, catalogueItemAIExtract.copy(name = Some(catalogueItemNameOther))),
          emptyEntryRows = List(3L),
          unidentifiedEntryRows = List(4L),
          extractionNotes = Nil,
        )

        catalogueItemNameOther shouldNot equal(catalogueItemName)

        aiExtractFromSpreadsheet(
          NonEmptyChunk.single(catalogueFileAIExtract)
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(catalogueItemCandidate, catalogueItemCandidate.copy(name = Some(catalogueItemNameOther))),
          infoNotes = List(
            "Processed 1 of 3 entries",
            "Catalogue items created 2",
            "Empty rows (1) at [3]",
            "Unidentified rows (1) at [4]",
          ),
          extractionNotes = Nil,
        )
      }

      "report the catalogue items and variants created when rows are grouped into one item" in new TestContext {
        val catalogueItemName             = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueItemVariantCandidate = CatalogueItemVariantCandidate(
          sku = None,
          label = None,
          description = None,
          attributes = Map.empty,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants =
                List(catalogueItemVariantAIExtract, catalogueItemVariantAIExtract, catalogueItemVariantAIExtract),
              extractionNotes = List("Three rows were grouped into one item"),
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromSpreadsheet(
          NonEmptyChunk.single(catalogueFileAIExtract)
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants =
                List(catalogueItemVariantCandidate, catalogueItemVariantCandidate, catalogueItemVariantCandidate),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Three rows were grouped into one item"),
            )
          ),
          infoNotes = List("Processed 3 of 3 entries", "Catalogue items created 1", "Item variants created 3"),
          extractionNotes = Nil,
        )
      }

      "extract a photo without processing guidance" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] = None
        val organizationID                                                            = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("guided-catalogue.jpeg"))
        val catalogueFileAIExtract         = CatalogueFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil)

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CatalogueFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCatalogueItemsFromImageInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .returns(ZIO.succeed(catalogueFileAIExtract))
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(Nil, List("Processed 0 of 0 entries"), Nil)
      }

      "forward photo processing guidance" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] =
          Some(CatalogueFileProcessingMessage("Omit prices from variants"))
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("guided-catalogue.jpeg"))
        val catalogueFileAIExtract         = CatalogueFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil)

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CatalogueFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCatalogueItemsFromImageInstructions,
              catalogueFileProcessingMessageOpt.map(_.value),
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .returns(ZIO.succeed(catalogueFileAIExtract))
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(Nil, List("Processed 0 of 0 entries"), Nil)
      }

      "extract a PDF without processing guidance, sending its declared file name, without note compaction" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] = None
        val organizationID                                                            = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue-price-list.pdf")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("scanned-catalogue.pdf"))
        val extractionNotes                = List("Left out the page footer", "Prices are in euros")
        val catalogueFileAIExtract         = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = List(23L),
          extractionNotes = extractionNotes,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.PDF, FileBytesSize.assume(1L))))
            .once(),
          (aiClientDataExtractionMock
            .extractFromPdf[CatalogueFileAIExtract](
              _: FileScannedPath,
              _: String,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              fileScannedPath,
              catalogueFileExtractFileName.value,
              AIInstructions.extractCatalogueItemsFromPdfInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .returns(ZIO.succeed(catalogueFileAIExtract))
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 1 entries", "Unidentified rows (1) at [23]"),
          extractionNotes = extractionNotes,
        )
      }

      "forward PDF processing guidance" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] =
          Some(CatalogueFileProcessingMessage("Read only the second price column"))
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue-price-list.pdf")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("scanned-catalogue.pdf"))
        val extractionNotes                = List("Left out the page footer", "Prices are in euros")
        val catalogueFileAIExtract         = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = List(23L),
          extractionNotes = extractionNotes,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.PDF, FileBytesSize.assume(1L))))
            .once(),
          (aiClientDataExtractionMock
            .extractFromPdf[CatalogueFileAIExtract](
              _: FileScannedPath,
              _: String,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              fileScannedPath,
              catalogueFileExtractFileName.value,
              AIInstructions.extractCatalogueItemsFromPdfInstructions,
              catalogueFileProcessingMessageOpt.map(_.value),
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .returns(ZIO.succeed(catalogueFileAIExtract))
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 1 entries", "Unidentified rows (1) at [23]"),
          extractionNotes = extractionNotes,
        )
      }

      "extract catalogue items from CSV" in new TestContext {
        val catalogueFileProcessingMessage = CatalogueFileProcessingMessage("Omit prices from variants")
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("catalogue.csv"))
        val csvRecords                     = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemName              = CatalogueItemName("Item")
        val catalogueFileAIExtract         = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(ZStream.fromIterable(csvRecords)).once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CatalogueFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              Some(catalogueFileProcessingMessage.value),
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk.single(catalogueFileAIExtract)
              }
            }
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            Some(catalogueFileProcessingMessage),
            catalogueFileExtractByteStream,
          )
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "extract catalogue items from XLS" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.xls")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("catalogue.xls"))
        val csvRecords                     = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemName              = CatalogueItemName("Item")
        val catalogueFileAIExtract         = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.XLS, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertExcelToCsv
            .expects(fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CatalogueFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk.single(catalogueFileAIExtract)
              }
            }
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "extract catalogue items from XLSX" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.xlsx")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("catalogue.xlsx"))
        val csvRecords                     = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemName              = CatalogueItemName("Item")
        val catalogueFileAIExtract         = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.XLSX, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertExcelToCsv
            .expects(fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CatalogueFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk.single(catalogueFileAIExtract)
              }
            }
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemName),
              description = None,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "merge spreadsheet batches in group order and replace the repeated top-level notes with the compacted notes" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("batched-catalogue.csv"))
        val csvRecords = CSVParser.parse("Name\nItem A\nItem B", CSVFormat.DEFAULT).getRecords.asScala.toList
        val catalogueItemNameFirst      = CatalogueItemName("Item A")
        val catalogueItemNameSecond     = CatalogueItemName("Item B")
        val catalogueFileAIExtractFirst = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemNameFirst),
              description = None,
              variants = Nil,
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = List(3L),
          unidentifiedEntryRows = List(4L),
          extractionNotes = List("Column D was not used", "First batch is cropped"),
        )
        val catalogueFileAIExtractSecond = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemNameSecond),
              description = None,
              variants = Nil,
              extractionNotes = Nil,
            )
          ),
          emptyEntryRows = List(6L),
          unidentifiedEntryRows = List(7L),
          extractionNotes = List("Column D was not used", "Second batch is blurry"),
        )
        val catalogueFileAIExtractNotesMerged = List(
          "Column D was not used",
          "First batch is cropped",
          "Column D was not used",
          "Second batch is blurry",
        )
        val noteCompactionOutput = NoteCompactionOutput(
          notes = List("Column D was not used", "First batch is cropped", "Second batch is blurry")
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(ZStream.fromIterable(csvRecords)).once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CatalogueFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk(catalogueFileAIExtractFirst, catalogueFileAIExtractSecond)
              }
            }
            .once(),
          aiClientDataExtractionMock.noteCompaction
            .expects(catalogueFileAIExtractNotesMerged, AIInstructions.compactCatalogueFileNotesInstructions)
            .returns(ZIO.succeed(noteCompactionOutput))
            .once(),
        )

        val response = buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = Some(catalogueItemNameFirst),
              description = None,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CatalogueItemCandidate(
              name = Some(catalogueItemNameSecond),
              description = None,
              variants = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/variants")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 2 of 6 entries", "Empty rows (2) at [3, 6]", "Unidentified rows (2) at [4, 7]"),
          extractionNotes = noteCompactionOutput.notes,
        )
      }

      "replace the top-level notes with the compacted notes when compaction returns as many notes as it was sent" in new TestContext {
        val catalogueFileAIExtractNotesGroup  = List("Some cells are merged")
        val catalogueFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput              = NoteCompactionOutput(
          notes = List("Some cells are merged in every group", "Column D looks like an internal reference")
        )

        noteCompactionOutput.notes.size shouldBe catalogueFileAIExtractNotesMerged.size
        noteCompactionOutput.notes shouldNot equal(catalogueFileAIExtractNotesMerged)

        aiExtractFromSpreadsheetWithTwoGroups(
          catalogueFileAIExtractNotesGroup,
          catalogueFileAIExtractNotesMerged,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = noteCompactionOutput.notes,
        )
      }

      "keep the raw top-level notes when note compaction fails with an UnexpectedError" in new TestContext {
        val catalogueFileAIExtractNotesGroup  = List("Some cells are merged")
        val catalogueFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val serviceErrorNoteCompaction        =
          ServiceError.InternalServerError.UnexpectedError(error = "Note compaction failed")

        aiExtractFromSpreadsheetWithTwoGroups(
          catalogueFileAIExtractNotesGroup,
          catalogueFileAIExtractNotesMerged,
          ZIO.fail(serviceErrorNoteCompaction),
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = catalogueFileAIExtractNotesMerged,
        )
      }

      "keep the raw top-level notes when note compaction dies with a defect" in new TestContext {
        val catalogueFileAIExtractNotesGroup  = List("Some cells are merged")
        val catalogueFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val runtimeExceptionNoteCompaction    = new RuntimeException("Note compaction defect")

        aiExtractFromSpreadsheetWithTwoGroups(
          catalogueFileAIExtractNotesGroup,
          catalogueFileAIExtractNotesMerged,
          ZIO.die(runtimeExceptionNoteCompaction),
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = catalogueFileAIExtractNotesMerged,
        )
      }

      "keep the raw top-level notes when note compaction returns no notes" in new TestContext {
        val catalogueFileAIExtractNotesGroup  = List("Some cells are merged")
        val catalogueFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput              = NoteCompactionOutput(notes = Nil)

        aiExtractFromSpreadsheetWithTwoGroups(
          catalogueFileAIExtractNotesGroup,
          catalogueFileAIExtractNotesMerged,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = catalogueFileAIExtractNotesMerged,
        )
      }

      "keep the raw top-level notes when note compaction returns more notes than it was sent" in new TestContext {
        val catalogueFileAIExtractNotesGroup  = List("Some cells are merged")
        val catalogueFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput              = NoteCompactionOutput(
          notes = List("Some cells are merged", "Column D looks like an internal reference", "Row 7 is blank")
        )

        noteCompactionOutput.notes.size should be > catalogueFileAIExtractNotesMerged.size

        aiExtractFromSpreadsheetWithTwoGroups(
          catalogueFileAIExtractNotesGroup,
          catalogueFileAIExtractNotesMerged,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = catalogueFileAIExtractNotesMerged,
        )
      }

      "keep an image's top-level notes without note compaction" in new TestContext {
        val extractionNotes        = List("Some labels are cropped", "Some labels are cropped")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = extractionNotes,
        )

        aiExtractFromImage(catalogueFileAIExtract) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = extractionNotes,
        )
      }

      "keep a single spreadsheet group's top-level notes without note compaction" in new TestContext {
        val extractionNotes        = List("Some cells are merged", "Some cells are merged")
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = extractionNotes,
        )

        aiExtractFromSpreadsheet(
          NonEmptyChunk.single(catalogueFileAIExtract)
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = extractionNotes,
        )
      }

      "not call note compaction for several spreadsheet groups without top-level notes" in new TestContext {
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 0L,
          candidates = Nil,
          emptyEntryRows = Nil,
          unidentifiedEntryRows = List(2L),
          extractionNotes = Nil,
        )

        aiExtractFromSpreadsheet(
          NonEmptyChunk(catalogueFileAIExtract, catalogueFileAIExtract.copy(unidentifiedEntryRows = List(102L)))
        ) shouldBe CatalogueFileAiExtractPostResponse(
          candidates = Nil,
          infoNotes = List("Processed 0 of 2 entries", "Unidentified rows (2) at [2, 102]"),
          extractionNotes = Nil,
        )
      }

      "report only field paths that resolve against the candidate JSON, to an existing value or, for a missing value, to its existing parent object" in new TestContext {
        val catalogueItemNameDuplicate    = CatalogueItemName("Bolt")
        val priceAmount                   = arbitrarySample[Price].amount
        val currency                      = arbitrarySample[Currency]
        val catalogueItemVariantSaleUnit  = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantAIExtract = CatalogueItemVariantAIExtract(
          sku = None,
          label = None,
          description = None,
          attributes = Nil,
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnit),
          sellingUnitInferred = false,
          extractionNotes = Nil,
        )
        val catalogueFileAIExtract = CatalogueFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(catalogueItemNameDuplicate),
              description = None,
              variants = List(
                catalogueItemVariantAIExtract.copy(
                  sku = Some(CatalogueItemVariantSku("AB-1")),
                  price = Some(
                    CatalogueItemVariantPriceAIExtract(
                      amount = priceAmount,
                      currency = Some(currency),
                      currencyInferred = true,
                    )
                  ),
                  sellingUnitInferred = true,
                ),
                catalogueItemVariantAIExtract.copy(
                  sku = Some(CatalogueItemVariantSku("ab-1")),
                  price = Some(
                    CatalogueItemVariantPriceAIExtract(amount = priceAmount, currency = None, currencyInferred = false)
                  ),
                  sellingUnit = None,
                ),
              ),
              extractionNotes = Nil,
            ),
            CatalogueItemAIExtract(
              name = Some(catalogueItemNameDuplicate),
              description = None,
              variants = Nil,
              extractionNotes = Nil,
            ),
            CatalogueItemAIExtract(
              name = None,
              description = None,
              variants = List(catalogueItemVariantAIExtract),
              extractionNotes = Nil,
            ),
          ),
          emptyEntryRows = Nil,
          unidentifiedEntryRows = Nil,
          extractionNotes = Nil,
        )

        def jsonPointerSegments(path: String): List[String] =
          path.split("/", -1).toList.drop(1).map(segment => segment.replace("~1", "/").replace("~0", "~"))

        def resolveJsonPointer(json: Json, segments: List[String]): Option[Json] =
          segments.foldLeft(Option(json))((jsonOpt, segment) =>
            jsonOpt.flatMap(jsonParent =>
              jsonParent.asObject
                .flatMap(jsonObject => jsonObject(segment))
                .orElse(jsonParent.asArray.flatMap(jsons => segment.toIntOption.flatMap(jsons.lift)))
            )
          )

        def schemaHasJsonPointer(schema: Schema[?], segments: List[String]): Boolean =
          (schema.schemaType, segments) match {
            case (_, Nil)                               => true
            case (SchemaType.SOption(schemaElement), _) => schemaHasJsonPointer(schemaElement, segments)
            case (SchemaType.SArray(schemaElement), segment :: segmentsRest) =>
              segment.toIntOption.isDefined && schemaHasJsonPointer(schemaElement, segmentsRest)
            case (SchemaType.SProduct(schemaFields), segment :: segmentsRest) =>
              schemaFields
                .find(schemaField => schemaField.name.encodedName == segment)
                .exists(schemaField => schemaHasJsonPointer(schemaField.schema, segmentsRest))
            case _ => false
          }

        def candidateJsons(responseJson: Json): List[Json] =
          responseJson.asObject.flatMap(jsonObject => jsonObject("candidates")).flatMap(_.asArray).toList.flatten

        def fieldInfoPaths(candidateJson: Json, fieldInfosProperty: String): List[String] =
          candidateJson.asObject
            .flatMap(jsonObject => jsonObject(fieldInfosProperty))
            .flatMap(_.asArray)
            .toList
            .flatten
            .flatMap(fieldInfoJson => fieldInfoJson.asObject.flatMap(jsonObject => jsonObject("path")))
            .flatMap(_.asString)

        val catalogueFileAiExtractPostResponseJson =
          parser
            .parse(writeToString(aiExtractFromImage(catalogueFileAIExtract)))
            .getOrElse(fail("The response did not serialize to JSON"))
        val fieldInfoPathsReported = candidateJsons(catalogueFileAiExtractPostResponseJson).flatMap(candidateJson =>
          List("inferredFields", "missingFields", "duplicateFields").flatMap(fieldInfosProperty =>
            fieldInfoPaths(candidateJson, fieldInfosProperty).map(path =>
              (candidateJson = candidateJson, fieldInfosProperty = fieldInfosProperty, path = path)
            )
          )
        )

        fieldInfoPathsReported.map(fieldInfoPathReported =>
          (fieldInfosProperty = fieldInfoPathReported.fieldInfosProperty, path = fieldInfoPathReported.path)
        ) shouldBe List(
          (fieldInfosProperty = "inferredFields", path = "/variants/0/sellingUnit"),
          (fieldInfosProperty = "inferredFields", path = "/variants/0/price/currency"),
          (fieldInfosProperty = "missingFields", path = "/variants/1/sellingUnit"),
          (fieldInfosProperty = "missingFields", path = "/variants/1/price/currency"),
          (fieldInfosProperty = "duplicateFields", path = "/name"),
          (fieldInfosProperty = "duplicateFields", path = "/variants/0/sku"),
          (fieldInfosProperty = "duplicateFields", path = "/variants/1/sku"),
          (fieldInfosProperty = "missingFields", path = "/variants"),
          (fieldInfosProperty = "duplicateFields", path = "/name"),
          (fieldInfosProperty = "missingFields", path = "/name"),
        )
        fieldInfoPathsReported.foreach(fieldInfoPathReported =>
          withClue(s"${fieldInfoPathReported.fieldInfosProperty} ${fieldInfoPathReported.path}: ") {
            val segments = jsonPointerSegments(fieldInfoPathReported.path)

            fieldInfoPathReported.path should startWith("/")
            schemaHasJsonPointer(catalogueItemCandidateSchema, segments) shouldBe true
            fieldInfoPathReported.fieldInfosProperty match {
              case "missingFields" =>
                resolveJsonPointer(fieldInfoPathReported.candidateJson, segments.init).flatMap(
                  _.asObject
                ) shouldBe defined
                resolveJsonPointer(fieldInfoPathReported.candidateJson, segments).filterNot(_.isNull) shouldBe None
              case _ =>
                resolveJsonPointer(fieldInfoPathReported.candidateJson, segments).filterNot(_.isNull) shouldBe defined
            }
          }
        )
      }

      "align a fully populated candidate with the catalogue item insert request" in new TestContext {
        val catalogueItemVariantInsert      = arbitrarySample[CatalogueItemVariantInsert]
        val catalogueItemVariantInsertFirst = catalogueItemVariantInsert.copy(
          sku = Some(arbitrarySample[CatalogueItemVariantSku]),
          label = Some(arbitrarySample[CatalogueItemVariantLabel]),
          description = Some(arbitrarySample[CatalogueItemVariantDescription]),
          attributes = Map(
            arbitrarySample[CatalogueItemVariantAttributeName] -> arbitrarySample[CatalogueItemVariantAttributeValue]
          ),
          price = Some(arbitrarySample[CatalogueItemVariantPrice]),
          sellingUnit = catalogueItemVariantInsert.sellingUnit.copy(
            perUnit = Some(arbitrarySample[CatalogueItemVariantPerUnit]),
            unitSize = Some(arbitrarySample[CatalogueItemVariantUnitSize]),
          ),
        )
        val catalogueItemVariantInsertSecond = arbitrarySample[CatalogueItemVariantInsert].copy(
          sku = Some(arbitrarySample[CatalogueItemVariantSku]),
          label = Some(arbitrarySample[CatalogueItemVariantLabel]),
          description = Some(arbitrarySample[CatalogueItemVariantDescription]),
          attributes = Map(
            arbitrarySample[CatalogueItemVariantAttributeName] -> arbitrarySample[CatalogueItemVariantAttributeValue]
          ),
          price = Some(arbitrarySample[CatalogueItemVariantPrice]),
          sellingUnit = arbitrarySample[CatalogueItemVariantSaleUnit].copy(
            perUnit = Some(arbitrarySample[CatalogueItemVariantPerUnit]),
            unitSize = Some(arbitrarySample[CatalogueItemVariantUnitSize]),
          ),
        )
        val catalogueItemPostRequest = arbitrarySample[CatalogueItemPostRequest].copy(
          description = Some(arbitrarySample[CatalogueItemDescription]),
          variants = NonEmptyChunk(catalogueItemVariantInsertFirst, catalogueItemVariantInsertSecond),
        )
        val catalogueItemCandidate = catalogueItemPostRequest
          .into[CatalogueItemCandidate]
          .withFieldConst(_.inferredFields, List(FieldInfo("/variants/0/sellingUnit")))
          .withFieldConst(_.missingFields, List(FieldInfo("/variants/1/price/currency")))
          .withFieldConst(_.duplicateFields, List(FieldInfo("/name")))
          .withFieldConst(_.needsAttention, true)
          .withFieldConst(_.extractionNotes, List("Prices come from the supplier list"))
          .transform

        val catalogueItemPostRequestDecoded =
          SmithyJson.read[smithy.CatalogueItemPostRequest](Blob(writeToString(catalogueItemCandidate)))

        catalogueItemPostRequestDecoded shouldBe Right(
          catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest]
        )
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
            SupportedMediaType.aiExtractData,
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

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.JPEG, FileBytesSize.assume(1L))))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CatalogueFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCatalogueItemsFromImageInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .returns(ZIO.fail(serviceError))
            .once(),
        )

        buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
      }

      "fail with an UnexpectedError when PDF AI extraction fails" in new TestContext {
        val catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage] = None
        val organizationID                                                            = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue-price-list.pdf")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("scanned-catalogue.pdf"))
        val serviceError = ServiceError.InternalServerError.UnexpectedError("Unable to extract catalogue")

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.PDF, FileBytesSize.assume(1L))))
            .once(),
          (aiClientDataExtractionMock
            .extractFromPdf[CatalogueFileAIExtract](
              _: FileScannedPath,
              _: String,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              fileScannedPath,
              catalogueFileExtractFileName.value,
              AIInstructions.extractCatalogueItemsFromPdfInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .returns(ZIO.fail(serviceError))
            .once(),
        )

        buildCatalogueFileService()
          .aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
          .zioError shouldBe serviceError
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
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(csvRecordStream).once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CatalogueFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .onCall { (csvRecordStreamSent, _, _, _, _) =>
              csvRecordStreamSent.runCollect *>
                ZIO.die(new IllegalStateException("The CSV record stream should fail with the conversion error"))
            }
            .once(),
        )

        buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
      }

      "fail with an UnexpectedError when spreadsheet AI extraction fails" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.csv")
        val catalogueFileExtractByteStream = ZStream.empty
        val fileScannedPath                = FileScannedPath(Path.of("failed-catalogue.csv"))
        val csvRecords                     = CSVParser.parse("Name\nItem", CSVFormat.DEFAULT).getRecords.asScala.toList
        val serviceError = ServiceError.InternalServerError.UnexpectedError("Failed spreadsheet AI extraction")

        inSequence(
          fileScannerMock.scan
            .expects(
              catalogueFileExtractByteStream,
              catalogueFileExtractFileName.value,
              SupportedMediaType.aiExtractData,
              catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
            )
            .returns(ZIO.succeed((fileScannedPath, SupportedMediaType.CSV, FileBytesSize.assume(1L))))
            .once(),
          spreadsheetToolMock.convertToCsv.expects(fileScannedPath).returns(ZStream.fromIterable(csvRecords)).once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CatalogueFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
              ai.catalogueFileAIExtractOpenAIJsonSchema,
              ai.catalogueFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.flatMap { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                ZIO.fail(serviceError)
              }
            }
            .once(),
        )

        buildCatalogueFileService()
          .aiExtract(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
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

    def buildCatalogueFileService(): CatalogueFileService[ServiceTask] = ZIO
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

    def aiExtractFromImage(catalogueFileAIExtract: CatalogueFileAIExtract): CatalogueFileAiExtractPostResponse = {
      val catalogueFileExtractFileName                 = CatalogueFileExtractFileName("catalogue.jpeg")
      val catalogueFileExtractByteStream               = ZStream.empty
      val fileScannerScanOutput: FileScannerScanOutput = (
        fileScannedPath = FileScannedPath(Path.of("catalogue.jpeg")),
        supportedMediaType = SupportedMediaType.JPEG,
        fileBytesSize = FileBytesSize.assume(1L),
      )

      inSequence(
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.aiExtractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once(),
        (aiClientDataExtractionMock
          .extractFromImage[CatalogueFileAIExtract](
            _: FileScannedPath,
            _: SupportedMediaType,
            _: String,
            _: Option[String],
          )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
          .expects(
            fileScannerScanOutput.fileScannedPath,
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
            ai.catalogueFileAIExtractOpenAIJsonSchema,
            ai.catalogueFileAIExtractCodec,
          )
          .returns(ZIO.succeed(catalogueFileAIExtract))
          .once(),
      )

      buildCatalogueFileService()
        .aiExtract(arbitrarySample[OrganizationID], catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
        .zioValue
    }

    def aiExtractFromSpreadsheet(
        catalogueFileAIExtracts: NonEmptyChunk[CatalogueFileAIExtract]
    ): CatalogueFileAiExtractPostResponse = {
      val catalogueFileExtractFileName                 = CatalogueFileExtractFileName("catalogue.csv")
      val catalogueFileExtractByteStream               = ZStream.empty
      val fileScannerScanOutput: FileScannerScanOutput = (
        fileScannedPath = FileScannedPath(Path.of("catalogue.csv")),
        supportedMediaType = SupportedMediaType.CSV,
        fileBytesSize = FileBytesSize.assume(1L),
      )
      val csvRecords: List[CSVRecord] = CSVParser.parse("Name\nBolt", CSVFormat.DEFAULT).getRecords.asScala.toList

      inSequence(
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.aiExtractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once(),
        spreadsheetToolMock.convertToCsv
          .expects(fileScannerScanOutput.fileScannedPath)
          .returns(ZStream.fromIterable(csvRecords))
          .once(),
        (aiClientDataExtractionMock
          .extractFromCsv[CatalogueFileAIExtract](
            _: ZStream[Scope, ServiceError, CSVRecord],
            _: String,
            _: Option[String],
          )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
          .expects(
            *,
            AIInstructions.extractCatalogueItemsFromFileInstructions,
            None,
            ai.catalogueFileAIExtractOpenAIJsonSchema,
            ai.catalogueFileAIExtractCodec,
          )
          .returns(ZIO.succeed(catalogueFileAIExtracts))
          .once(),
      )

      buildCatalogueFileService()
        .aiExtract(arbitrarySample[OrganizationID], catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
        .zioValue
    }

    def aiExtractFromSpreadsheetWithTwoGroups(
        catalogueFileAIExtractNotesGroup: List[String],
        catalogueFileAIExtractNotesMerged: List[String],
        noteCompactionResult: IO[ServiceError, NoteCompactionOutput],
    ): CatalogueFileAiExtractPostResponse = {
      val catalogueFileExtractFileName                 = CatalogueFileExtractFileName("catalogue.csv")
      val catalogueFileExtractByteStream               = ZStream.empty
      val fileScannerScanOutput: FileScannerScanOutput = (
        fileScannedPath = FileScannedPath(Path.of("catalogue.csv")),
        supportedMediaType = SupportedMediaType.CSV,
        fileBytesSize = FileBytesSize.assume(1L),
      )
      val csvRecords: List[CSVRecord] = CSVParser.parse("Name\nBolt", CSVFormat.DEFAULT).getRecords.asScala.toList
      val catalogueFileAIExtractGroup = CatalogueFileAIExtract(
        entriesIdentified = 0L,
        entriesProcessed = 0L,
        candidates = Nil,
        emptyEntryRows = Nil,
        unidentifiedEntryRows = Nil,
        extractionNotes = catalogueFileAIExtractNotesGroup,
      )

      inSequence(
        fileScannerMock.scan
          .expects(
            catalogueFileExtractByteStream,
            catalogueFileExtractFileName.value,
            SupportedMediaType.aiExtractData,
            catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once(),
        spreadsheetToolMock.convertToCsv
          .expects(fileScannerScanOutput.fileScannedPath)
          .returns(ZStream.fromIterable(csvRecords))
          .once(),
        (aiClientDataExtractionMock
          .extractFromCsv[CatalogueFileAIExtract](
            _: ZStream[Scope, ServiceError, CSVRecord],
            _: String,
            _: Option[String],
          )(using _: OpenAIJsonSchema[CatalogueFileAIExtract], _: JsonValueCodec[CatalogueFileAIExtract]))
          .expects(
            *,
            AIInstructions.extractCatalogueItemsFromFileInstructions,
            None,
            ai.catalogueFileAIExtractOpenAIJsonSchema,
            ai.catalogueFileAIExtractCodec,
          )
          .returns(ZIO.succeed(NonEmptyChunk(catalogueFileAIExtractGroup, catalogueFileAIExtractGroup)))
          .once(),
        aiClientDataExtractionMock.noteCompaction
          .expects(catalogueFileAIExtractNotesMerged, AIInstructions.compactCatalogueFileNotesInstructions)
          .returns(noteCompactionResult)
          .once(),
      )

      buildCatalogueFileService()
        .aiExtract(arbitrarySample[OrganizationID], catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
        .zioValue
    }
  }
}
