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

    private val singleOccurrenceCount = 1

    private def mergeCatalogueFileAIExtracts(
        catalogueFileAIExtracts: NonEmptyChunk[CatalogueFileAIExtract]
    ): CatalogueFileAIExtract = {
      val catalogueFileAIExtractsList = catalogueFileAIExtracts.toList
      val catalogueItemAIExtracts     = catalogueFileAIExtractsList.flatMap(_.candidates)

      val catalogueItemNameCounts = catalogueItemAIExtracts.groupMapReduce(
        _.name.value.toLowerCase(Locale.ROOT)
      )(_ => singleOccurrenceCount)(_ + _)

      val catalogueItemVariantSkuCounts = catalogueItemAIExtracts
        .flatMap(_.variants.flatMap(_.sku))
        .groupMapReduce(_.value.toLowerCase(Locale.ROOT))(_ => singleOccurrenceCount)(_ + _)

      val candidates = catalogueItemAIExtracts.map { catalogueItemAIExtract =>
        catalogueItemAIExtract.copy(
          nameIsDuplicate =
            catalogueItemNameCounts(catalogueItemAIExtract.name.value.toLowerCase(Locale.ROOT)) > singleOccurrenceCount,
          variants = catalogueItemAIExtract.variants.map { catalogueItemVariantAIExtract =>
            catalogueItemVariantAIExtract.copy(
              skuIsDuplicate = catalogueItemVariantAIExtract.sku.exists(catalogueItemVariantSku =>
                catalogueItemVariantSkuCounts(
                  catalogueItemVariantSku.value.toLowerCase(Locale.ROOT)
                ) > singleOccurrenceCount
              )
            )
          },
        )
      }

      CatalogueFileAIExtract(
        entriesIdentified = catalogueFileAIExtracts.map(_.entriesIdentified).sum,
        entriesProcessed = catalogueFileAIExtracts.map(_.entriesProcessed).sum,
        candidates = candidates,
        emptyEntryRows = catalogueFileAIExtractsList.flatMap(_.emptyEntryRows),
        unidentifiedEntryRows = catalogueFileAIExtractsList.flatMap(_.unidentifiedEntryRows),
        extractionNotes = catalogueFileAIExtractsList.flatMap(_.extractionNotes),
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
              .map(mergeCatalogueFileAIExtracts)
          case supportedMediaTypeUnexpected =>
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unexpected supported media type: [$supportedMediaTypeUnexpected]"
              )
            )
        }

        candidates = catalogueFileAIExtract.candidates.map { catalogueItemAIExtract =>
          val extractionNotesComputed = computeExtractNotes(catalogueItemAIExtract)
          val needsAttentionComputed  = computeNeedsAttention(catalogueItemAIExtract)

          val variants = catalogueItemAIExtract.variants
            .map(
              _.intoPartial[CatalogueItemVariantInsert]
                .withFieldComputed(_.attributes, _.attributes.map(a => a.name -> a.value).toMap)
                .transform
                .asOption
            )
            .flatMap(_.toList)

          catalogueItemAIExtract
            .into[CatalogueItemCandidate]
            .withFieldConst(_.variants, variants)
            .withFieldConst(_.needsAttention, needsAttentionComputed)
            .withFieldConst(_.extractionNotes, extractionNotesComputed)
            .transform
        }
        infoNotesComputed = computeInfoNotes(catalogueFileAIExtract)
      } yield CatalogueFileAiExtractPostResponse(
        candidates = candidates,
        infoNotes = infoNotesComputed,
        extractionNotes = catalogueFileAIExtract.extractionNotes,
      )
    )

    private def computeInfoNotes(catalogueFileAIExtract: CatalogueFileAIExtract): List[String] =
      List(
        s"Processed ${catalogueFileAIExtract.entriesProcessed} of ${catalogueFileAIExtract.entriesIdentified} entries"
      ) ++
        Option
          .when(catalogueFileAIExtract.emptyEntryRows.nonEmpty)(
            s"Empty rows (${catalogueFileAIExtract.emptyEntryRows.size}): [${catalogueFileAIExtract.emptyEntryRows.mkString(", ")}]"
          )
          .toList ++
        Option
          .when(catalogueFileAIExtract.unidentifiedEntryRows.nonEmpty)(
            s"Unidentified rows (${catalogueFileAIExtract.unidentifiedEntryRows.size}): [${catalogueFileAIExtract.unidentifiedEntryRows.mkString(", ")}]"
          )
          .toList

    private def computeNeedsAttention(catalogueItemAIExtract: CatalogueItemAIExtract): Boolean =
      catalogueItemAIExtract.atLeastOneVariant || catalogueItemAIExtract.nameIsDuplicate || catalogueItemAIExtract.variants
        .exists(catalogueItemVariantAIExtract =>
          catalogueItemVariantAIExtract.missingRequiredSaleUnit || catalogueItemVariantAIExtract.skuIsDuplicate || catalogueItemVariantAIExtract.sellingUnit.isEmpty
        )

    private def computeExtractNotes(catalogueItemAIExtract: CatalogueItemAIExtract): List[String] =
      if (catalogueItemAIExtract.atLeastOneVariant)
        Option.when(catalogueItemAIExtract.atLeastOneVariant)("At least one variant is required.").toList ++
          Option.when(catalogueItemAIExtract.nameIsDuplicate)("Item name is duplicated in this extraction.").toList ++
          catalogueItemAIExtract.extractionNotes
      else
        Option
          .when(catalogueItemAIExtract.nameIsDuplicate)("Item name is duplicated in this extraction.")
          .toList ++ catalogueItemAIExtract.extractionNotes ++
          catalogueItemAIExtract.variants.zipWithIndex.flatMap { case (variant, index) =>
            val prefix = if (catalogueItemAIExtract.variants.sizeIs > 1) s"Variant ${index + 1}: " else ""
            Option
              .when(variant.missingRequiredSaleUnit || variant.sellingUnit.isEmpty)(
                prefix + "Selling unit is missing."
              )
              .toList ++
              Option.when(variant.skuIsDuplicate)(prefix + "SKU is duplicated in this extraction.").toList ++
              variant.extractionNotes.map(prefix + _)
          }
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
