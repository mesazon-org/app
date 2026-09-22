package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.extractCustomersPostResponseCodec
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.*
import org.apache.commons.csv.CSVRecord
import zio.*
import zio.stream.*

import java.util.Locale

trait FileService[F[_]] {
  def uploadOrganizationLogo(
      organizationID: OrganizationID,
      organizationLogoImageOriginalFileName: ImageOriginalFileName,
      organizationLogoImageByteStream: ZStream[Any, Throwable, Byte],
  ): F[Unit]

  def uploadCatalogueItemImage(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemImageOriginalFileName: ImageOriginalFileName,
      catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
  ): F[Unit]

  def extractCustomers(
      organizationID: OrganizationID,
      extractCustomersFileName: ExtractCustomersFileName,
      extractCustomersFileByteStream: ZStream[Any, Throwable, Byte],
  ): F[ExtractCustomersPostResponse]
}

object FileService {

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      catalogueRepository: CatalogueRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      spreadsheetTool: SpreadsheetTool,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
      aiClientDataExtraction: AIClientDataExtraction,
  ) extends FileService[ServiceTask] {

    private def normalizedName(name: String): String = name.toLowerCase(Locale.ROOT)

    private def mergeExtractCustomersPostResponses(
        responses: NonEmptyChunk[ExtractCustomersPostResponse]
    ): ZIO[Any, ServiceError, ExtractCustomersPostResponse] = for {
      responseList         = responses.toChunk.toList
      individualCandidates = responseList.flatMap(_.customerIndividualCandidates)
      businessCandidates   = responseList.flatMap(_.customerBusinessCandidates)
      individualNameCounts = individualCandidates.groupMapReduce(candidate =>
        normalizedName(candidate.candidate.fullName.value)
      )(_ => 1)(_ + _)
      businessNameCounts = businessCandidates.groupMapReduce(candidate =>
        normalizedName(candidate.candidate.businessName.value)
      )(_ => 1)(_ + _)
      unidentifiedEntriesNotesAll = responseList.flatMap(_.unidentifiedEntriesNotes)
      entriesIdentifiedSum        = responses.map(_.entriesIdentified).sum
      entriesProcessedSum         = responses.map(_.entriesProcessed).sum
      emptyEntryRowsAll           = responseList.flatMap(_.emptyEntryRows)
      unidentifiedEntryRowsAll    = responseList.flatMap(_.unidentifiedEntryRows)
      unidentifiedEntriesNotesOpt <- ZIO.when(unidentifiedEntriesNotesAll.nonEmpty)(
        aiClientDataExtraction
          .noteCompaction(
            entriesIdentifiedSum,
            entriesProcessedSum,
            emptyEntryRowsAll,
            unidentifiedEntryRowsAll,
            unidentifiedEntriesNotesAll,
            AIInstructions.noteCompactionInstructions,
          )
          .map(_.note)
          .catchAllCause(cause =>
            ZIO.logErrorCause("Failed to compact unidentified entries notes", cause) *>
              ZIO.succeed(
                unidentifiedEntriesNotesFallback(
                  entriesIdentifiedSum,
                  entriesProcessedSum,
                  emptyEntryRowsAll,
                  unidentifiedEntryRowsAll,
                )
              )
          )
      )
      extractCustomersPostResponse = ExtractCustomersPostResponse(
        entriesIdentified = entriesIdentifiedSum,
        entriesProcessed = entriesProcessedSum,
        emptyEntryRows = emptyEntryRowsAll,
        unidentifiedEntryRows = unidentifiedEntryRowsAll,
        unidentifiedEntriesNotes = unidentifiedEntriesNotesOpt,
        customerIndividualCandidates = individualCandidates.map(candidate =>
          candidate.copy(
            isDuplicate = individualNameCounts(normalizedName(candidate.candidate.fullName.value)) > 1
          )
        ),
        customerBusinessCandidates = businessCandidates.map(candidate =>
          candidate.copy(
            isDuplicate = businessNameCounts(normalizedName(candidate.candidate.businessName.value)) > 1
          )
        ),
      )
    } yield extractCustomersPostResponse

    private def unidentifiedEntriesNotesFallback(
        entriesIdentified: Long,
        entriesProcessed: Long,
        emptyEntryRows: List[Long],
        unidentifiedEntryRows: List[Long],
    ): String = {
      val emptyEntryRowsSentence =
        Option.when(emptyEntryRows.nonEmpty)(s" Rows ${emptyEntryRows.mkString(", ")} were blank.").getOrElse("")
      val unidentifiedEntryRowsSentence = Option
        .when(unidentifiedEntryRows.nonEmpty)(
          s" Rows ${unidentifiedEntryRows.mkString(", ")} had no name we could read."
        )
        .getOrElse("")

      s"$entriesProcessed of $entriesIdentified entries could be added automatically." +
        emptyEntryRowsSentence + unidentifiedEntryRowsSentence
    }

    private def extractFromCsvRecordStream(
        csvRecordStream: ZStream[Scope, ServiceError, CSVRecord]
    ): ZIO[Scope, ServiceError, ExtractCustomersPostResponse] =
      aiClientDataExtraction
        .extractFromCsv[ExtractCustomersPostResponse](
          csvRecordStream,
          AIInstructions.extractCustomersFromFileInstructions,
        )
        .flatMap(mergeExtractCustomersPostResponses)

    override def uploadOrganizationLogo(
        organizationID: OrganizationID,
        organizationLogoImageOriginalFileName: ImageOriginalFileName,
        organizationLogoImageByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO.scoped(for {
      organizationLogoImageScanOutput <- fileScanner.scan(
        organizationLogoImageByteStream,
        organizationLogoImageOriginalFileName.value,
        SupportedMediaType.images,
        fileServiceConfig.fileBytesMax,
      )
      organizationLogoImageNormalizedResult <- imageProcessing.normalize(
        FileByteStreamScanned(ZStream.fromPath(organizationLogoImageScanOutput.fileScannedPath.value)),
        SupportedMediaType.images,
      )
      organizationLogoImageUploadedResult <-
        s3ClientOrganizationMedia
          .uploadImageOrganizationLogo(
            organizationID,
            organizationLogoImageNormalizedResult.imageOriginalByteStream,
            organizationLogoImageNormalizedResult.imageNormalizedByteStream,
          )
      imageAsset = ImageAsset(
        imageOriginalS3BucketKey = organizationLogoImageUploadedResult.imageOriginalS3BucketKey,
        imageNormalizedS3BucketKey = organizationLogoImageUploadedResult.imageNormalizedS3BucketKey,
        imageOriginalFileName = organizationLogoImageOriginalFileName,
      )
      organizationLogoImageAsset <- ZIO
        .fromEither(OrganizationLogoImageAsset.either(imageAsset))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to construct OrganizationLogoImageAsset: [$e]"
          )
        )
      _ <- organizationManagementRepository
        .updateOrganization(
          organizationID = organizationID,
          organizationStageOptUpdate = Some(OrganizationStage.LogoProvided),
          logoImageAssetOptUpdate = Some(organizationLogoImageAsset),
        )
    } yield ())

    override def uploadCatalogueItemImage(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemImageOriginalFileName: ImageOriginalFileName,
        catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO
      .scoped(for {
        catalogueItemRow <- catalogueRepository
          .getCatalogueItem(organizationID, catalogueItemID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Catalogue item not found: catalogueItemID=[$catalogueItemID]"
            )
          )
        _ <-
          if (catalogueItemRow.status != CatalogueItemStatus.Active) {
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Catalogue item is not active: catalogueItemID=[$catalogueItemID], status=[${catalogueItemRow.status}]"
              )
            )
          } else {
            ZIO.unit
          }
        catalogueItemImageScanOutput <- fileScanner.scan(
          catalogueItemImageByteStream,
          catalogueItemImageOriginalFileName.value,
          SupportedMediaType.images,
          fileServiceConfig.fileBytesMax,
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
          imageAssetOptUpdate = Some(catalogueItemImageAsset),
        )
      } yield ())

    override def extractCustomers(
        organizationID: OrganizationID,
        extractCustomersFileName: ExtractCustomersFileName,
        extractCustomersFileByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[ExtractCustomersPostResponse] = ZIO.scoped(for {
      customerBookScanOutput <- fileScanner.scan(
        extractCustomersFileByteStream,
        extractCustomersFileName.value,
        SupportedMediaType.extractData,
        fileServiceConfig.fileBytesMax,
      )
      extractCustomersPostResponse <- customerBookScanOutput.supportedMediaType match {
        case supportedMediaType if SupportedMediaType.images.contains(supportedMediaType) =>
          aiClientDataExtraction.extractFromImage[ExtractCustomersPostResponse](
            customerBookScanOutput.fileScannedPath,
            supportedMediaType,
            AIInstructions.extractCustomersFromImageInstructions,
          )
        case supportedMediaType if SupportedMediaType.excel.contains(supportedMediaType) =>
          extractFromCsvRecordStream(spreadsheetTool.convertExcelToCsv(customerBookScanOutput.fileScannedPath))
        case supportedMediaType if SupportedMediaType.csv.contains(supportedMediaType) =>
          extractFromCsvRecordStream(spreadsheetTool.convertToCsv(customerBookScanOutput.fileScannedPath))
        case supportedMediaTypeUnexpected =>
          ZIO.fail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Unexpected supported media type: [$supportedMediaTypeUnexpected]"
            )
          )
      }
    } yield extractCustomersPostResponse)

  }

  def observed(service: FileService[ServiceTask]): FileService[TapirTask] =
    new FileService[TapirTask] {
      override def uploadOrganizationLogo(
          organizationID: OrganizationID,
          organizationLogoImageOriginalFileName: ImageOriginalFileName,
          organizationLogoImageByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service
            .uploadOrganizationLogo(
              organizationID,
              organizationLogoImageOriginalFileName,
              organizationLogoImageByteStream,
            )
        )

      override def uploadCatalogueItemImage(
          organizationID: OrganizationID,
          catalogueItemID: CatalogueItemID,
          catalogueItemImageOriginalFileName: ImageOriginalFileName,
          catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service
            .uploadCatalogueItemImage(
              organizationID,
              catalogueItemID,
              catalogueItemImageOriginalFileName,
              catalogueItemImageByteStream,
            )
        )

      override def extractCustomers(
          organizationID: OrganizationID,
          extractCustomersFileName: ExtractCustomersFileName,
          extractCustomersFileByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[ExtractCustomersPostResponse] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.extractCustomers(
            organizationID,
            extractCustomersFileName,
            extractCustomersFileByteStream,
          )
        )

    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
