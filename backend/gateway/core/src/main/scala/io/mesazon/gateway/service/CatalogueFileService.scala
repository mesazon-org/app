package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.{AIClientDataExtraction, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.CatalogueFileServiceConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.*
import io.scalaland.chimney.dsl.*
import zio.*
import zio.stream.ZStream

import java.util.Locale

trait CatalogueFileService[F[_]] {
  def uploadImageItem(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemImageOriginalFileName: ImageOriginalFileName,
      catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
  ): F[Unit]

  def aiExtract(
      organizationID: OrganizationID,
      catalogueFileExtractFileName: CatalogueFileExtractFileName,
      catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage],
      catalogueFileExtractByteStream: ZStream[Any, Throwable, Byte],
  ): F[CatalogueFileAiExtractPostResponse]
}

object CatalogueFileService {

  private final class CatalogueFileServiceImpl(
      catalogueFileServiceConfig: CatalogueFileServiceConfig,
      catalogueRepository: CatalogueRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      spreadsheetTool: SpreadsheetTool,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
      aiClientDataExtraction: AIClientDataExtraction,
  ) extends CatalogueFileService[ServiceTask] {

    private type CatalogueItemFieldsComputed = (
        inferredFields: List[FieldInfo],
        missingFields: List[FieldInfo],
        duplicateFields: List[FieldInfo],
    )

    private inline val SingleOccurrenceCount = 1

    private inline val NoteCompactionGroupCountMin   = 2
    private inline val CatalogueItemVariantsCountMin = 1

    private inline val NameProperty = "name"

    private inline val VariantsProperty = "variants"

    private inline val SkuProperty = "sku"

    private inline val SellingUnitProperty = "sellingUnit"

    private inline val PriceProperty = "price"

    private inline val CurrencyProperty = "currency"

    private def mergeCatalogueFileAIExtracts(
        catalogueFileAIExtracts: NonEmptyChunk[CatalogueFileAIExtract]
    ): CatalogueFileAIExtract = {
      val catalogueFileAIExtractsList = catalogueFileAIExtracts.toList

      CatalogueFileAIExtract(
        entriesIdentified = catalogueFileAIExtracts.map(_.entriesIdentified).sum,
        entriesProcessed = catalogueFileAIExtracts.map(_.entriesProcessed).sum,
        candidates = catalogueFileAIExtractsList.flatMap(_.candidates),
        emptyEntryRows = catalogueFileAIExtractsList.flatMap(_.emptyEntryRows),
        unidentifiedEntryRows = catalogueFileAIExtractsList.flatMap(_.unidentifiedEntryRows),
        extractionNotes = catalogueFileAIExtractsList.flatMap(_.extractionNotes),
      )
    }

    private def computeExtractionNotesCompactedOpt(
        extractionNotesMerged: List[String],
        noteCompactionOutput: NoteCompactionOutput,
    ): Option[List[String]] =
      Option.when(
        noteCompactionOutput.notes.nonEmpty && noteCompactionOutput.notes.size <= extractionNotesMerged.size
      )(noteCompactionOutput.notes)

    private def computeInfoNotes(catalogueFileAIExtract: CatalogueFileAIExtract): List[String] = {
      val catalogueItemsCreatedCount        = catalogueFileAIExtract.candidates.size.toLong
      val catalogueItemVariantsCreatedCount =
        catalogueFileAIExtract.candidates.map(_.variants.size.max(CatalogueItemVariantsCountMin)).sum.toLong

      List(
        Some(
          s"Processed ${catalogueFileAIExtract.entriesProcessed} of ${catalogueFileAIExtract.entriesIdentified} entries"
        ),
        Option
          .when(catalogueItemsCreatedCount != catalogueFileAIExtract.entriesProcessed)(
            s"Catalogue items created $catalogueItemsCreatedCount"
          ),
        Option
          .when(catalogueItemVariantsCreatedCount != catalogueItemsCreatedCount)(
            s"Item variants created $catalogueItemVariantsCreatedCount"
          ),
        Option
          .when(catalogueFileAIExtract.emptyEntryRows.nonEmpty)(
            s"Empty rows (${catalogueFileAIExtract.emptyEntryRows.size}) at [${catalogueFileAIExtract.emptyEntryRows.mkString(", ")}]"
          ),
        Option
          .when(catalogueFileAIExtract.unidentifiedEntryRows.nonEmpty)(
            s"Unidentified rows (${catalogueFileAIExtract.unidentifiedEntryRows.size}) at [${catalogueFileAIExtract.unidentifiedEntryRows.mkString(", ")}]"
          ),
      ).flatten
    }

    private def computeCatalogueItemNameCounts(
        catalogueItemAIExtracts: List[CatalogueItemAIExtract]
    ): Map[CatalogueItemName, Int] =
      catalogueItemAIExtracts.flatMap(_.name).groupMapReduce(identity)(_ => SingleOccurrenceCount)(_ + _)

    private def computeCatalogueItemVariantSkuCounts(
        catalogueItemAIExtracts: List[CatalogueItemAIExtract]
    ): Map[String, Int] =
      catalogueItemAIExtracts
        .flatMap(_.variants.flatMap(_.sku))
        .groupMapReduce(_.value.toLowerCase(Locale.ROOT))(_ => SingleOccurrenceCount)(_ + _)

    private def computeCatalogueItemVariantFields(
        catalogueItemVariantAIExtract: CatalogueItemVariantAIExtract,
        catalogueItemVariantIndex: Int,
        catalogueItemVariantSkuCounts: Map[String, Int],
    ): CatalogueItemFieldsComputed = {
      lazy val sellingUnitFieldInfo =
        FieldInfo.fromPathSegments(VariantsProperty, catalogueItemVariantIndex, SellingUnitProperty)
      lazy val currencyFieldInfo =
        FieldInfo.fromPathSegments(VariantsProperty, catalogueItemVariantIndex, PriceProperty, CurrencyProperty)
      lazy val skuFieldInfo = FieldInfo.fromPathSegments(VariantsProperty, catalogueItemVariantIndex, SkuProperty)

      (
        inferredFields = Option
          .when(
            catalogueItemVariantAIExtract.sellingUnit.isDefined && catalogueItemVariantAIExtract.sellingUnitInferred
          )(sellingUnitFieldInfo)
          .toList ++ Option
          .when(
            catalogueItemVariantAIExtract.price.exists(catalogueItemVariantPriceAIExtract =>
              catalogueItemVariantPriceAIExtract.currency.isDefined && catalogueItemVariantPriceAIExtract.currencyInferred
            )
          )(currencyFieldInfo)
          .toList,
        missingFields = Option.when(catalogueItemVariantAIExtract.sellingUnit.isEmpty)(sellingUnitFieldInfo).toList ++
          Option
            .when(catalogueItemVariantAIExtract.price.exists(_.currency.isEmpty))(currencyFieldInfo)
            .toList,
        duplicateFields = Option
          .when(
            catalogueItemVariantAIExtract.sku.exists(catalogueItemVariantSku =>
              catalogueItemVariantSkuCounts
                .get(catalogueItemVariantSku.value.toLowerCase(Locale.ROOT))
                .exists(_ > SingleOccurrenceCount)
            )
          )(skuFieldInfo)
          .toList,
      )
    }

    private def computeCatalogueItemFields(
        catalogueItemAIExtract: CatalogueItemAIExtract,
        catalogueItemNameCounts: Map[CatalogueItemName, Int],
        catalogueItemVariantSkuCounts: Map[String, Int],
    ): CatalogueItemFieldsComputed = {
      val catalogueItemVariantFieldsComputed = catalogueItemAIExtract.variants.zipWithIndex.map {
        case (catalogueItemVariantAIExtract, catalogueItemVariantIndex) =>
          computeCatalogueItemVariantFields(
            catalogueItemVariantAIExtract,
            catalogueItemVariantIndex,
            catalogueItemVariantSkuCounts,
          )
      }

      (
        inferredFields = catalogueItemVariantFieldsComputed.flatMap(_.inferredFields),
        missingFields =
          Option.when(catalogueItemAIExtract.name.isEmpty)(FieldInfo.fromPathSegments(NameProperty)).toList ++
            Option
              .when(catalogueItemAIExtract.variants.isEmpty)(FieldInfo.fromPathSegments(VariantsProperty))
              .toList ++ catalogueItemVariantFieldsComputed.flatMap(_.missingFields),
        duplicateFields = Option
          .when(
            catalogueItemAIExtract.name.exists(catalogueItemName =>
              catalogueItemNameCounts.get(catalogueItemName).exists(_ > SingleOccurrenceCount)
            )
          )(FieldInfo.fromPathSegments(NameProperty))
          .toList ++ catalogueItemVariantFieldsComputed.flatMap(_.duplicateFields),
      )
    }

    override def uploadImageItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemImageOriginalFileName: ImageOriginalFileName,
        catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO
      .scoped(for {
        _ <- catalogueRepository
          .getCatalogueItemWithVariants(organizationID, catalogueItemID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Catalogue item not found: catalogueItemID=[$catalogueItemID]"
            )
          )
        catalogueItemImageScanOutput <- fileScanner.scan(
          catalogueItemImageByteStream,
          catalogueItemImageOriginalFileName.value,
          SupportedMediaType.images,
          catalogueFileServiceConfig.uploadCatalogueItemImageFileBytesMax,
        )
        catalogueItemImageNormalizedResult <- imageProcessing.normalize(
          FileByteStreamScanned(ZStream.fromPath(catalogueItemImageScanOutput.fileScannedPath.value)),
          SupportedMediaType.images,
        )
        catalogueItemImageUploadedResult <-
          s3ClientOrganizationMedia
            .uploadImageCatalogueItem(
              organizationID,
              catalogueItemID,
              catalogueItemImageNormalizedResult.imageOriginalByteStream,
              catalogueItemImageNormalizedResult.imageNormalizedByteStream,
            )
        imageAsset = ImageAsset(
          imageOriginalS3BucketKey = catalogueItemImageUploadedResult.imageOriginalS3BucketKey,
          imageNormalizedS3BucketKey = catalogueItemImageUploadedResult.imageNormalizedS3BucketKey,
          imageOriginalFileName = catalogueItemImageOriginalFileName,
        )
        catalogueItemImageAsset <- ZIO
          .fromEither(CatalogueItemImageAsset.either(imageAsset))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to construct CatalogueItemImageAsset: [$e]"
            )
          )
        _ <- catalogueRepository.updateCatalogueItem(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          nameOptUpdate = None,
          descriptionOptUpdate = None,
          imageAssetOptUpdate = Some(catalogueItemImageAsset),
          statusOptUpdate = None,
          updateCatalogueItemVariantInputs = Nil,
          insertCatalogueItemVariantInputs = Nil,
        )
      } yield ())

    override def aiExtract(
        organizationID: OrganizationID,
        catalogueFileExtractFileName: CatalogueFileExtractFileName,
        catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage],
        catalogueFileExtractByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[CatalogueFileAiExtractPostResponse] = ZIO.scoped(
      for {
        catalogueFileScanOutput <- fileScanner.scan(
          catalogueFileExtractByteStream,
          catalogueFileExtractFileName.value,
          SupportedMediaType.extractData,
          catalogueFileServiceConfig.extractCatalogueItemsFileBytesMax,
        )
        catalogueFileAIExtract <- catalogueFileScanOutput.supportedMediaType match {
          case supportedMediaType if SupportedMediaType.images.contains(supportedMediaType) =>
            aiClientDataExtraction.extractFromImage[CatalogueFileAIExtract](
              catalogueFileScanOutput.fileScannedPath,
              supportedMediaType,
              AIInstructions.extractCatalogueItemsFromImageInstructions,
              catalogueFileProcessingMessageOpt.map(_.value),
            )
          case supportedMediaType if SupportedMediaType.spreadsheets.contains(supportedMediaType) =>
            val csvRecordStream =
              if (SupportedMediaType.excel.contains(supportedMediaType))
                spreadsheetTool.convertExcelToCsv(catalogueFileScanOutput.fileScannedPath)
              else spreadsheetTool.convertToCsv(catalogueFileScanOutput.fileScannedPath)

            aiClientDataExtraction
              .extractFromCsv[CatalogueFileAIExtract](
                csvRecordStream,
                AIInstructions.extractCatalogueItemsFromFileInstructions,
                catalogueFileProcessingMessageOpt.map(_.value),
              )
              .flatMap { catalogueFileAIExtracts =>
                val catalogueFileAIExtractMerged = mergeCatalogueFileAIExtracts(catalogueFileAIExtracts)
                val extractionNotesMerged        = catalogueFileAIExtractMerged.extractionNotes

                if (catalogueFileAIExtracts.size < NoteCompactionGroupCountMin || extractionNotesMerged.isEmpty)
                  ZIO.succeed(catalogueFileAIExtractMerged)
                else
                  aiClientDataExtraction
                    .noteCompaction(extractionNotesMerged, AIInstructions.compactCatalogueFileNotesInstructions)
                    .flatMap(noteCompactionOutput =>
                      computeExtractionNotesCompactedOpt(extractionNotesMerged, noteCompactionOutput) match {
                        case Some(extractionNotesCompacted) => ZIO.succeed(extractionNotesCompacted)
                        case None                           =>
                          ZIO
                            .logWarning(
                              s"Note compaction returned [${noteCompactionOutput.notes.size}] notes for [${extractionNotesMerged.size}] sent, keeping the raw notes"
                            )
                            .as(extractionNotesMerged)
                      }
                    )
                    .catchAllCause(cause =>
                      ZIO
                        .logWarningCause("Note compaction failed, keeping the raw notes", cause)
                        .as(extractionNotesMerged)
                    )
                    .map(extractionNotes => catalogueFileAIExtractMerged.copy(extractionNotes = extractionNotes))
              }
          case supportedMediaTypeUnexpected =>
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unexpected supported media type: [$supportedMediaTypeUnexpected]"
              )
            )
        }

        catalogueItemNameCounts       = computeCatalogueItemNameCounts(catalogueFileAIExtract.candidates)
        catalogueItemVariantSkuCounts = computeCatalogueItemVariantSkuCounts(catalogueFileAIExtract.candidates)
        candidates                    = catalogueFileAIExtract.candidates.map { catalogueItemAIExtract =>
          val catalogueItemFieldsComputed =
            computeCatalogueItemFields(catalogueItemAIExtract, catalogueItemNameCounts, catalogueItemVariantSkuCounts)

          catalogueItemAIExtract
            .into[CatalogueItemCandidate]
            .withFieldComputedFrom(_.variants.everyItem)(
              _.variants.everyItem.attributes,
              _.attributes
                .map(catalogueItemVariantAttributesAIExtract =>
                  catalogueItemVariantAttributesAIExtract.name -> catalogueItemVariantAttributesAIExtract.value
                )
                .toMap,
            )
            .withFieldConst(_.inferredFields, catalogueItemFieldsComputed.inferredFields)
            .withFieldConst(_.missingFields, catalogueItemFieldsComputed.missingFields)
            .withFieldConst(_.duplicateFields, catalogueItemFieldsComputed.duplicateFields)
            .withFieldConst(
              _.needsAttention,
              catalogueItemFieldsComputed.missingFields.nonEmpty || catalogueItemFieldsComputed.duplicateFields.nonEmpty,
            )
            .withFieldConst(
              _.extractionNotes,
              catalogueItemAIExtract.extractionNotes ++ catalogueItemAIExtract.variants.flatMap(_.extractionNotes),
            )
            .transform
        }
        infoNotesComputed = computeInfoNotes(catalogueFileAIExtract)
      } yield CatalogueFileAiExtractPostResponse(
        candidates = candidates,
        infoNotes = infoNotesComputed,
        extractionNotes = catalogueFileAIExtract.extractionNotes,
      )
    )
  }

  def observed(service: CatalogueFileService[ServiceTask]): CatalogueFileService[TapirTask] =
    new CatalogueFileService[TapirTask] {
      override def uploadImageItem(
          organizationID: OrganizationID,
          catalogueItemID: CatalogueItemID,
          catalogueItemImageOriginalFileName: ImageOriginalFileName,
          catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.uploadImageItem(
            organizationID,
            catalogueItemID,
            catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream,
          )
        )

      override def aiExtract(
          organizationID: OrganizationID,
          catalogueFileExtractFileName: CatalogueFileExtractFileName,
          catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage],
          catalogueFileExtractByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[CatalogueFileAiExtractPostResponse] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.aiExtract(
            organizationID,
            catalogueFileExtractFileName,
            catalogueFileProcessingMessageOpt,
            catalogueFileExtractByteStream,
          )
        )
    }

  val local = ZLayer
    .derive[CatalogueFileServiceImpl]
    .project[CatalogueFileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
