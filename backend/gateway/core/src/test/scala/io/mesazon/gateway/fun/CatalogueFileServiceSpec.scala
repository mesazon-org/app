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
import org.apache.commons.csv.CSVRecord
import org.scalamock.matchers.ArgCapture.CaptureOne
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

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

          val response = buildCatalogueFileService()
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

        val serviceError = buildCatalogueFileService()
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

        val serviceError = buildCatalogueFileService()
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
          .extractCatalogueItems(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileExtractPostResponse(
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
          .extractCatalogueItems(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioValue

        response shouldBe CatalogueFileExtractPostResponse(
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
              extractionNotes = List("Source is blurry", "Variant 1: Label is partly obscured"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      List[(String, CatalogueItemAIExtract => CatalogueItemAIExtract, String, Int)](
        (
          "an item requires a variant",
          item => item.copy(atLeastOneVariant = true, variants = Nil),
          "At least one variant is required.",
          0,
        ),
        (
          "an item name is duplicated",
          item => item.copy(nameIsDuplicate = true),
          "Item name is duplicated in this extraction.",
          1,
        ),
        (
          "a variant selling unit is missing",
          item => item.copy(variants = item.variants.map(_.copy(sellingUnit = None, missingRequiredSaleUnit = true))),
          "Variant 1: Selling unit is missing.",
          0,
        ),
        (
          "a variant SKU is duplicated",
          item => item.copy(variants = item.variants.map(_.copy(skuIsDuplicate = true))),
          "Variant 1: SKU is duplicated in this extraction.",
          1,
        ),
        (
          "an AI variant lacks a selling unit without setting its flag",
          item => item.copy(variants = item.variants.map(_.copy(sellingUnit = None))),
          "Variant 1: Selling unit is missing.",
          0,
        ),
      ).foreach { case (condition, flaggedItem, expectedNote, expectedVariantCount) =>
        s"mark the candidate for attention and add a note when $condition" in new TestContext {
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
            CatalogueFileAIExtract(1L, 1L, List(flaggedItem(catalogueItemAIExtract)), Nil, Nil, Nil)
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
            .extractCatalogueItems(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
            .zioValue

          response shouldBe CatalogueFileExtractPostResponse(
            candidates = List(
              CatalogueItemCandidate(
                name = catalogueItemAIExtract.name,
                description = catalogueItemAIExtract.description,
                variants = if (expectedVariantCount == 0) Nil else List(catalogueItemVariantInsert),
                needsAttention = true,
                extractionNotes = List(expectedNote),
              )
            ),
            infoNotes = List("Processed 1 of 1 entries"),
            extractionNotes = Nil,
          )
        }
      }

      List[(String, List[Long], List[Long], List[String])](
        ("only the count summary", Nil, Nil, Nil),
        ("only empty-row findings", List(2L, 4L), Nil, List("Empty rows (2): [2, 4]")),
        ("only unidentified-row findings", Nil, List(6L), List("Unidentified rows (1): [6]")),
      ).foreach { case (summary, emptyEntryRows, unidentifiedEntryRows, rowNotes) =>
        s"return an empty photo preview with $summary" in new TestContext {
          val organizationID                 = arbitrarySample[OrganizationID]
          val catalogueFileExtractFileName   = CatalogueFileExtractFileName("catalogue.jpeg")
          val catalogueFileExtractByteStream = ZStream.empty
          val fileScannedPath                = FileScannedPath(Path.of("empty-catalogue.jpeg"))
          val extractionNotes                = List("No readable catalogue entries were found")
          val catalogueFileAIExtract         =
            CatalogueFileAIExtract(0L, 0L, Nil, emptyEntryRows, unidentifiedEntryRows, extractionNotes)
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
            .extractCatalogueItems(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
            .zioValue
          response shouldBe CatalogueFileExtractPostResponse(
            candidates = Nil,
            infoNotes = List("Processed 0 of 0 entries") ++ rowNotes,
            extractionNotes = extractionNotes,
          )
        }
      }

      List[(String, Option[CatalogueFileProcessingMessage])](
        ("without optional guidance", None),
        ("with optional guidance", Some(CatalogueFileProcessingMessage("Omit prices from variants"))),
      ).foreach { case (guidance, catalogueFileProcessingMessageOpt) =>
        s"forward the default photo instructions separately from processing guidance $guidance" in new TestContext {
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
            .extractCatalogueItems(
              organizationID,
              catalogueFileExtractFileName,
              catalogueFileProcessingMessageOpt,
              catalogueFileExtractByteStream,
            )
            .zioValue
          response shouldBe CatalogueFileExtractPostResponse(Nil, List("Processed 0 of 0 entries"), Nil)
          extractFromImageCallsRef.refValue shouldBe List(
            (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions)
          )
          extractFromImageProcessingMessagesRef.refValue shouldBe List(catalogueFileProcessingMessageOpt.map(_.value))
          extractFromCsvCallsRef.refValue shouldBe Nil
          noteCompactionCallsRef.refValue shouldBe Nil
        }
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
          .extractCatalogueItems(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
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
          .extractCatalogueItems(organizationID, catalogueFileExtractFileName, None, catalogueFileExtractByteStream)
          .zioError shouldBe serviceError
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions)
        )
        extractFromCsvCallsRef.refValue shouldBe Nil
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
