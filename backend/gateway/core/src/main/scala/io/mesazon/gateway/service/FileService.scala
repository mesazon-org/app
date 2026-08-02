package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.{OrganizationLogosS3Client, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.{CatalogueRepository, OrganizationManagementRepository}
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.*
import zio.*
import zio.stream.*

trait FileService[F[_]] {
  def uploadOrganizationLogo(
      organizationID: OrganizationID,
      organizationLogoOriginalFileName: OrganizationLogoOriginalFileName,
      organizationLogoFile: ZStream[Any, Throwable, Byte],
  ): F[Unit]

  def uploadCatalogueItemImage(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemImageOriginalFileName: ImageOriginalFileName,
      catalogueItemImageFile: ZStream[Any, Throwable, Byte],
  ): F[Unit]
}

object FileService {

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      catalogueRepository: CatalogueRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      organizationLogosS3Client: OrganizationLogosS3Client,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
  ) extends FileService[ServiceTask] {

    override def uploadOrganizationLogo(
        organizationID: OrganizationID,
        organizationLogoOriginalFileName: OrganizationLogoOriginalFileName,
        organizationLogoByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO.scoped(for {
      organizationLogoScannedByteStream <- fileScanner.scan(
        organizationLogoByteStream,
        SupportedMediaTypes.images,
        fileServiceConfig.maxUploadBytes,
      )
      organizationLogoNormalizedResult <- imageProcessing.normalize(
        organizationLogoScannedByteStream,
        SupportedMediaTypes.images,
      )
      organizationUploadLogosResult <-
        organizationLogosS3Client
          .upload(
            organizationID,
            organizationLogoNormalizedResult.imageOriginalByteStream,
            organizationLogoNormalizedResult.imageNormalizedByteStream,
          )
      _ <- organizationManagementRepository
        .updateOrganization(
          organizationID = organizationID,
          organizationStageOptUpdate = Some(OrganizationStage.LogoProvided),
          logoOriginalBucketKeyOptUpdate = Some(organizationUploadLogosResult.organizationLogoOriginalBucketKey),
          logoNormalizedBucketKeyOptUpdate = Some(organizationUploadLogosResult.organizationLogoNormalizedBucketKey),
          logoOriginalFileNameOptUpdate = Some(organizationLogoOriginalFileName),
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
        catalogueItemImageScannedByteStream <- fileScanner.scan(
          catalogueItemImageByteStream,
          SupportedMediaTypes.images,
          fileServiceConfig.maxUploadBytes,
        )
        catalogueItemImageNormalizedResult <- imageProcessing.normalize(
          catalogueItemImageScannedByteStream,
          SupportedMediaTypes.images,
        )
        catalogueItemUploadImagesResult <-
          s3ClientOrganizationMedia
            .upload(
              organizationID,
              catalogueItemID,
              catalogueItemImageNormalizedResult.imageOriginalByteStream,
              catalogueItemImageNormalizedResult.imageNormalizedByteStream,
            )
        imageObject = Image(
          originalBucketKey = catalogueItemUploadImagesResult.catalogueItemImageOriginalBucketKey,
          normalizedBucketKey = catalogueItemUploadImagesResult.catalogueItemImageNormalizedBucketKey,
          originalFileName = catalogueItemImageOriginalFileName,
        )
        catalogueItemImage <- ZIO
          .fromEither(CatalogueItemImage.either(imageObject))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to construct CatalogueItemImage: [$e]"
            )
          )
        _ <- catalogueRepository.updateCatalogueItem(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageOptUpdate = Some(catalogueItemImage),
        )
      } yield ())
      .catchAll(err => ZIO.logErrorCause("uploadCatalogueItemImage failed", Cause.fail(err)) *> ZIO.fail(err))
  }

  def observed(service: FileService[ServiceTask]): FileService[TapirTask] =
    new FileService[TapirTask] {
      override def uploadOrganizationLogo(
          organizationID: OrganizationID,
          organizationLogoOriginalFileName: OrganizationLogoOriginalFileName,
          organizationLogoFile: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service
            .uploadOrganizationLogo(organizationID, organizationLogoOriginalFileName, organizationLogoFile)
        )

      override def uploadCatalogueItemImage(
          organizationID: OrganizationID,
          catalogueItemID: CatalogueItemID,
          catalogueItemImageOriginalFileName: ImageOriginalFileName,
          catalogueItemImageFile: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service
            .uploadCatalogueItemImage(
              organizationID,
              catalogueItemID,
              catalogueItemImageOriginalFileName,
              catalogueItemImageFile,
            )
        )
    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
