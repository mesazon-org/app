package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.OrganizationLogosS3Client
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.OrganizationManagementRepository
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
}

object FileService {

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      organizationLogosS3Client: OrganizationLogosS3Client,
  ) extends FileService[ServiceTask] {

    override def uploadOrganizationLogo(
        organizationID: OrganizationID,
        organizationLogoOriginalFileName: OrganizationLogoOriginalFileName,
        organizationLogoByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO.scoped(for {
      organizationLogoScannedByteStream <- fileScanner.scan(
        organizationLogoByteStream,
        SupportedMediaTypes.images,
        fileServiceConfig.maxOrganizationLogoBytes,
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
          organizationStageUpdate = Some(OrganizationStage.LogoProvided),
          logoOriginalBucketKeyUpdate = Some(organizationUploadLogosResult.organizationLogoOriginalBucketKey),
          logoNormalizedBucketKeyUpdate = Some(organizationUploadLogosResult.organizationLogoNormalizedBucketKey),
          logoOriginalFileNameUpdate = Some(organizationLogoOriginalFileName),
        )
    } yield ())
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
    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
