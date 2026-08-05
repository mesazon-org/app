package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.{CatalogueRepository, OrganizationManagementRepository}
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.*
import zio.*
import zio.stream.*

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
}

object FileService {

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      catalogueRepository: CatalogueRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
  ) extends FileService[ServiceTask] {

    override def uploadOrganizationLogo(
        organizationID: OrganizationID,
        organizationLogoImageOriginalFileName: ImageOriginalFileName,
        organizationLogoImageByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO.scoped(for {
      organizationLogoImageScannedByteStream <- fileScanner.scan(
        organizationLogoImageByteStream,
        SupportedMediaTypes.images,
        fileServiceConfig.maxUploadBytes,
      )
      organizationLogoImageNormalizedResult <- imageProcessing.normalize(
        organizationLogoImageScannedByteStream,
        SupportedMediaTypes.images,
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
        catalogueItemImageScannedByteStream <- fileScanner.scan(
          catalogueItemImageByteStream,
          SupportedMediaTypes.images,
          fileServiceConfig.maxUploadBytes,
        )
        catalogueItemImageNormalizedResult <- imageProcessing.normalize(
          catalogueItemImageScannedByteStream,
          SupportedMediaTypes.images,
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
    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
