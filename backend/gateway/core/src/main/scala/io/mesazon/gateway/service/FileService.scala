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

  private[gateway] val extractCustomersFromImageInstructions =
    """You are reading an image of a paper customer list, grid, or business card for a business-management product.
      |Find every entry that looks like a person or a business the caller trades with.
      |For each entry, decide freely whether it looks like an individual or a business - there is no fixed rule
      |mapping a source (e.g. a business card) to one kind or the other.
      |Only return an entry as a candidate if you can make out a name for it. If you cannot make out any name for
      |an entry, do not return it as a candidate at all - just count it.
      |For a candidate business, only include a business contact if you can make out that contact's name.
      |For every field other than a name, try to produce a realistic, usable value (a real-looking email, a phone
      |number with enough information to be dialed, trimmed non-empty text) but this is best-effort, not required
      |to be perfectly accurate. For each phone, make a best-effort attempt to provide a valid phone pair for its
      |country. phoneNationalNumber must contain only the national number, without the country code, and
      |phoneCountryCode must contain the country dialling code. For example:
      |{"phoneNationalNumber":"99123456","phoneCountryCode":"+357"}
      |{"phoneNationalNumber":"4155550123","phoneCountryCode":"+1"}
      |Email and phone lists may be empty. Whenever either list is non-empty, mark exactly one entry in that list
      |with isDefault=true and mark every other entry with isDefault=false. Never send an empty string for an
      |optional field - omit the field entirely instead.
      |If something about a candidate is missing or unclear (e.g. a smudged phone number, no visible email), say so
      |in one short, plain sentence in that candidate's extraction notes; otherwise leave the notes out entirely.
      |Compare candidates only against each other within this same image, never against any other data. Two
      |candidates of the same kind (both individuals or both businesses) whose names match once capitalisation is
      |ignored are each a duplicate of the other; two candidates of different kinds are never duplicates of each
      |other even when their names match.
      |Report how many entries the image seemed to contain in total, and how many of those you actually turned into
      |candidates. If some entries could not be turned into candidates, add one short, plain-text summary line
      |describing what could not be read and where in the image to look; otherwise leave that summary out entirely.
      |If the image has nothing recognizable as a customer at all, return both counts as zero and empty candidate
      |lists rather than treating that as an error.""".stripMargin

  private[gateway] val extractCustomersFromFileInstructions =
    """You are reading either a CSV file or a plain-text table taken from the first sheet of a spreadsheet for a
      |business-management product. There is no fixed column layout - any column you don't recognize is simply
      |ignored, and a completely blank row is not an entry at all: do not count it, identify it, or mention it in
      |the summary.
      |You are receiving one ordered batch from the file. The first row is the header, and every subsequent
      |non-blank row is exactly one entry that must be examined. Process every data row in this batch.
      |Find every entry that looks like a person or a business the caller trades with. When separate first-name
      |and last-name columns are present, combine their non-empty values into the candidate's full name.
      |For each entry, decide freely whether it looks like an individual or a business - there is no fixed rule
      |mapping a row to one kind or the other.
      |Only return an entry as a candidate if you can make out a name for it. If you cannot make out any name for
      |an entry, do not return it as a candidate at all - just count it.
      |Every row with a readable name must produce exactly one candidate in the appropriate candidate list and
      |must be included in entriesProcessed. Do not omit named candidates to shorten the response.
      |For a candidate business, only include a business contact if you can make out that contact's name, for
      |example from an extra column with a name in it. This is best-effort only: it is never guaranteed, and no
      |particular column or layout is required for it to happen.
      |For every field other than a name, try to produce a realistic, usable value (a real-looking email, a phone
      |number with enough information to be dialed, trimmed non-empty text) but this is best-effort, not required
      |to be perfectly accurate. For each phone, make a best-effort attempt to provide a valid phone pair for its
      |country. phoneNationalNumber must contain only the national number, without the country code, and
      |phoneCountryCode must contain the country dialling code. For example:
      |{"phoneNationalNumber":"99123456","phoneCountryCode":"+357"}
      |{"phoneNationalNumber":"4155550123","phoneCountryCode":"+1"}
      |Email and phone lists may be empty. Whenever either list is non-empty, mark exactly one entry in that list
      |with isDefault=true and mark every other entry with isDefault=false. Never send an empty string for an
      |optional field - omit the field entirely instead.
      |If something about a candidate is missing or unclear (e.g. a blank cell, no visible email), say so in one
      |short, plain sentence in that candidate's extraction notes; otherwise leave the notes out entirely.
      |Compare candidates only against each other within this same file, never against any other data. Two
      |candidates of the same kind (both individuals or both businesses) whose names match once capitalisation is
      |ignored are each a duplicate of the other; two candidates of different kinds are never duplicates of each
      |other even when their names match.
      |Report how many entries the file seemed to contain in total, and how many of those you actually turned into
      |candidates. If some entries could not be turned into candidates, add one short, plain-text summary line
      |describing what could not be read and where in the file to look; otherwise leave that summary out entirely.
      |If the file has nothing recognizable as a customer at all, return both counts as zero and empty candidate
      |lists rather than treating that as an error.""".stripMargin

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      catalogueRepository: CatalogueRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      spreadsheetTool: SpreadsheetTool,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
      aiClient: AIClient,
  ) extends FileService[ServiceTask] {

    private def normalizedName(name: String): String = name.toLowerCase(Locale.ROOT)

    private def mergeExtractCustomersPostResponses(
        responses: NonEmptyChunk[ExtractCustomersPostResponse]
    ): ExtractCustomersPostResponse = {
      val responseList         = responses.toChunk.toList
      val individualCandidates = responseList.flatMap(_.customerIndividualCandidates)
      val businessCandidates   = responseList.flatMap(_.customerBusinessCandidates)
      val individualNameCounts = individualCandidates.groupMapReduce(candidate =>
        normalizedName(candidate.candidate.fullName.value)
      )(_ => 1)(_ + _)
      val businessNameCounts = businessCandidates.groupMapReduce(candidate =>
        normalizedName(candidate.candidate.businessName.value)
      )(_ => 1)(_ + _)
      val unidentifiedEntriesSummary = responseList
        .flatMap(_.unidentifiedEntriesSummary)
        .mkString("\n")

      ExtractCustomersPostResponse(
        entriesIdentified = responseList.map(_.entriesIdentified).sum,
        entriesProcessed = responseList.map(_.entriesProcessed).sum,
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
        unidentifiedEntriesSummary = Option.when(unidentifiedEntriesSummary.nonEmpty)(unidentifiedEntriesSummary),
      )
    }

    private def extractFromCsvValidatedPath(
        csvValidatedPath: CsvValidatedPath
    ): ServiceTask[ExtractCustomersPostResponse] =
      aiClient
        .extractFromCsv[ExtractCustomersPostResponse](
          csvValidatedPath,
          extractCustomersFromFileInstructions,
        )
        .map(mergeExtractCustomersPostResponses)

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
          aiClient.extractFromImage[ExtractCustomersPostResponse](
            customerBookScanOutput.fileScannedPath,
            supportedMediaType,
            extractCustomersFromImageInstructions,
          )
        case supportedMediaType if SupportedMediaType.excel.contains(supportedMediaType) =>
          spreadsheetTool
            .convertExcelToCsv(customerBookScanOutput.fileScannedPath)
            .flatMap(extractFromCsvValidatedPath)
        case supportedMediaType if SupportedMediaType.csv.contains(supportedMediaType) =>
          spreadsheetTool
            .convertToCsv(customerBookScanOutput.fileScannedPath)
            .flatMap(extractFromCsvValidatedPath)
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
