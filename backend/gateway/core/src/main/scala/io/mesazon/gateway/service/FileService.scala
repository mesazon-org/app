package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.OrganizationS3Client
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.OrganizationManagementRepository
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.ImageProcessing.OrganizationLogosProcessed
import io.mesazon.gateway.utils.{FileScanner, ImageProcessing}
import zio.*
import zio.stream.*

trait FileService[F[_]] {
  def uploadOrganizationLogo(
      organizationID: OrganizationID,
      organizationLogoFileName: OrganizationLogoFileName,
      organizationLogoFile: ZStream[Any, Throwable, Byte],
  ): F[Unit]
}

object FileService {

  private final class FileServiceImpl(
      fileServiceConfig: FileServiceConfig,
      organizationManagementRepository: OrganizationManagementRepository,
      fileScanner: FileScanner,
      imageProcessing: ImageProcessing,
      organizationS3Client: OrganizationS3Client,
  ) extends FileService[ServiceTask] {

    override def uploadOrganizationLogo(
        organizationID: OrganizationID,
        organizationLogoFileName: OrganizationLogoFileName,
        organizationLogoFile: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ZIO.scoped(for {
      organizationDetailsRow <- organizationManagementRepository
        .getOrganization(organizationID)
        .someOrFail(
          ServiceError.InternalServerError.UnexpectedError(s"Organization not found for ID: [$organizationID]")
        )
      organizationLogoBytesScanned <- fileScanner.scan(
        organizationLogoFile,
        SupportedMediaTypes.images,
        fileServiceConfig.maxOrganizationLogoBytes,
      )
      organizationLogosProcessed <- imageProcessing.processOrganizationLogo(
        organizationLogoBytesScanned,
        SupportedMediaTypes.images,
      )
      organizationLogoBucketKey <-
        organizationS3Client
          .uploadLogos(organizationID, organizationLogoFileName, organizationLogoFile)
      _ <- organizationManagementRepository
        .updateOrganization(
          organizationID = organizationID,
          organizationStage = OrganizationStage.LogoProvided,
          logoBucketKeyUpdate = Some(organizationLogoBucketKey),
          logoFileNameUpdate = Some(organizationLogoFileName),
        )
      _ <- ZIO
        .foreachDiscard(organizationDetailsRow.logoBucketKey) {
          case organizationLogoBucketKeyOld if organizationLogoBucketKey != organizationLogoBucketKeyOld =>
            organizationS3Client.deleteLogo(organizationLogoBucketKeyOld).orDie
          case _ => ZIO.unit
        }
        .catchAllCause(cause =>
          ZIO.logErrorCause(
            s"Failed to delete old organization logo for organizationID: [$organizationID]",
            cause,
          )
        )
    } yield ())
  }

  def observed(service: FileService[ServiceTask]): FileService[TapirTask] =
    new FileService[TapirTask] {
      override def uploadOrganizationLogo(
          organizationID: OrganizationID,
          organizationLogoFileName: OrganizationLogoFileName,
          organizationLogoFile: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service
            .uploadOrganizationLogo(organizationID, organizationLogoFileName, organizationLogoFile)
        )
    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
