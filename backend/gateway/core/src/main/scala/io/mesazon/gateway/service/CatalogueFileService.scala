package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.{AIClientDataExtraction, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.{FileScanner, ImageProcessing, SpreadsheetTool}
import zio.*
import zio.stream.ZStream

import scala.annotation.unused

trait CatalogueFileService[F[_]] {
  def uploadCatalogueItemImage(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemImageOriginalFileName: ImageOriginalFileName,
      catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
  ): F[Unit]

  def extractCatalogueItems(
      organizationID: OrganizationID,
      catalogueFileExtractFileName: CatalogueFileExtractFileName,
      catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage],
      catalogueFileExtractByteStream: ZStream[Any, Throwable, Byte],
  ): F[CatalogueFileExtractPostResponse]
}

object CatalogueFileService {

  private final class CatalogueFileServiceImpl(
      @unused fileServiceConfig: FileServiceConfig,
      @unused catalogueRepository: CatalogueRepository,
      @unused fileScanner: FileScanner,
      @unused imageProcessing: ImageProcessing,
      @unused spreadsheetTool: SpreadsheetTool,
      @unused s3ClientOrganizationMedia: S3ClientOrganizationMedia,
      @unused aiClientDataExtraction: AIClientDataExtraction,
  ) extends CatalogueFileService[ServiceTask] {

    override def uploadCatalogueItemImage(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemImageOriginalFileName: ImageOriginalFileName,
        catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] =
      ZIO.die(new NotImplementedError("Catalogue item image upload is not implemented in CatalogueFileService"))

    override def extractCatalogueItems(
        organizationID: OrganizationID,
        catalogueFileExtractFileName: CatalogueFileExtractFileName,
        catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage],
        catalogueFileExtractByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[CatalogueFileExtractPostResponse] =
      ZIO.die(new NotImplementedError("Catalogue item extraction is not implemented"))
  }

  def observed(service: CatalogueFileService[ServiceTask]): CatalogueFileService[TapirTask] =
    new CatalogueFileService[TapirTask] {
      override def uploadCatalogueItemImage(
          organizationID: OrganizationID,
          catalogueItemID: CatalogueItemID,
          catalogueItemImageOriginalFileName: ImageOriginalFileName,
          catalogueItemImageByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[Unit] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.uploadCatalogueItemImage(
            organizationID,
            catalogueItemID,
            catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream,
          )
        )

      override def extractCatalogueItems(
          organizationID: OrganizationID,
          catalogueFileExtractFileName: CatalogueFileExtractFileName,
          catalogueFileProcessingMessageOpt: Option[CatalogueFileProcessingMessage],
          catalogueFileExtractByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[CatalogueFileExtractPostResponse] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.extractCatalogueItems(
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
