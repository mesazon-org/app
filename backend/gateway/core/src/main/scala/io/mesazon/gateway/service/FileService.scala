package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.*
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
}

object FileService {

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
  ) extends FileService[ServiceTask] {

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
    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
