package io.mesazon.gateway.service

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.CatalogueRepositoryV2
import io.mesazon.gateway.repository.CatalogueRepositoryV2.*
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.validation.service.CatalogueV2RequestValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.scalaland.chimney.dsl.*
import zio.*

import java.util.UUID
import scala.annotation.unused

object CatalogueServiceV2 {

  private final class CatalogueServiceV2Impl(
      catalogueV2RequestValidator: CatalogueV2RequestValidator,
      catalogueRepositoryV2: CatalogueRepositoryV2,
      @unused s3ClientOrganizationMedia: S3ClientOrganizationMedia,
  ) extends smithy.v2.CatalogueServiceV2[ServiceTask] {

    override def catalogueItemPost(
        organizationID: UUID,
        request: smithy.v2.CatalogueItemPostRequest,
    ): ServiceTask[smithy.v2.CatalogueItemPostResponse] =
      for {
        catalogueItemPostRequest <- catalogueV2RequestValidator.validatedCatalogueItemPostRequest(request)
        catalogueItemID          <- catalogueRepositoryV2.insertCatalogueItem(
          OrganizationID(organizationID),
          catalogueItemPostRequest
            .into[InsertCatalogueItemInput]
            .withFieldComputed(
              _.variants,
              _.variants.map(
                _.into[InsertCatalogueItemVariantInput]
                  .withFieldConst(_.status, CatalogueItemVariantStatus.Active)
                  .transform
              ),
            )
            .transform,
        )
      } yield smithy.v2.CatalogueItemPostResponse(catalogueItemID.value)

    override def catalogueItemBatchPost(
        organizationID: UUID,
        request: smithy.v2.CatalogueItemBatchPostRequest,
    ): ServiceTask[smithy.v2.CatalogueItemBatchPostResponse] =
      for {
        catalogueItemBatchPostRequest <- catalogueV2RequestValidator.validatedCatalogueItemBatchPostRequest(request)
        catalogueItemIDs              <- catalogueRepositoryV2.insertCatalogueItems(
          OrganizationID(organizationID),
          catalogueItemBatchPostRequest.catalogueItems.map(
            _.into[InsertCatalogueItemInput]
              .withFieldComputed(
                _.variants,
                _.variants.map(
                  _.into[InsertCatalogueItemVariantInput]
                    .withFieldConst(_.status, CatalogueItemVariantStatus.Active)
                    .transform
                ),
              )
              .transform
          ),
        )
      } yield smithy.v2.CatalogueItemBatchPostResponse(catalogueItemIDs.transformInto[List[UUID]])

    override def catalogueItemPut(
        organizationID: UUID,
        catalogueItemID: UUID,
        request: smithy.v2.CatalogueItemPutRequest,
    ): ServiceTask[Unit] =
      for {
        catalogueItemPutRequest <- catalogueV2RequestValidator.validatedCatalogueItemPutRequest(request)
        _                       <- catalogueRepositoryV2.updateCatalogueItem(
          organizationID = OrganizationID(organizationID),
          catalogueItemID = CatalogueItemID(catalogueItemID),
          nameOptUpdate = catalogueItemPutRequest.name,
          descriptionOptUpdate = catalogueItemPutRequest.description,
          imageAssetOptUpdate = None,
          updateCatalogueItemVariantInputs = catalogueItemPutRequest.variantsUpdate
            .transformInto[List[UpdateCatalogueItemVariantInput]],
          insertCatalogueItemVariantInputs = catalogueItemPutRequest.variantsInsert.map(
            _.into[InsertCatalogueItemVariantInput]
              .withFieldConst(_.status, CatalogueItemVariantStatus.Active)
              .transform
          ),
        )
      } yield ()

    override def catalogueItemArchivePut(
        organizationID: UUID,
        catalogueItemID: UUID,
    ): ServiceTask[Unit] =
      catalogueRepositoryV2.archiveCatalogueItem(
        OrganizationID(organizationID),
        CatalogueItemID(catalogueItemID),
      )

    override def catalogueItemVariantArchivePut(
        organizationID: UUID,
        catalogueItemID: UUID,
        catalogueItemVariantID: UUID,
    ): ServiceTask[Unit] =
      ZIO.die(new NotImplementedError("CatalogueServiceV2.catalogueItemVariantArchivePut"))

    override def catalogueItemGet(
        organizationID: UUID,
        catalogueItemID: UUID,
    ): ServiceTask[smithy.v2.CatalogueItemGetResponse] =
      ZIO.die(new NotImplementedError("CatalogueServiceV2.catalogueItemGet"))

    override def catalogueItemSummaryGet(
        organizationID: UUID,
        catalogueItemIDs: List[UUID],
    ): ServiceTask[smithy.v2.CatalogueItemSummaryGetResponse] =
      ZIO.die(new NotImplementedError("CatalogueServiceV2.catalogueItemSummaryGet"))

    override def catalogueItemUniquenessGet(
        organizationID: UUID,
        name: Option[String],
        sku: Option[String],
    ): ServiceTask[smithy.v2.CatalogueItemUniquenessGetResponse] =
      ZIO.die(new NotImplementedError("CatalogueServiceV2.catalogueItemUniquenessGet"))
  }

  private def observed(service: smithy.v2.CatalogueServiceV2[ServiceTask]): smithy.v2.CatalogueServiceV2[Task] =
    new smithy.v2.CatalogueServiceV2[Task] {
      override def catalogueItemPost(
          organizationID: UUID,
          request: smithy.v2.CatalogueItemPostRequest,
      ): Task[smithy.v2.CatalogueItemPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemPost(organizationID, request))

      override def catalogueItemBatchPost(
          organizationID: UUID,
          request: smithy.v2.CatalogueItemBatchPostRequest,
      ): Task[smithy.v2.CatalogueItemBatchPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemBatchPost(organizationID, request))

      override def catalogueItemPut(
          organizationID: UUID,
          catalogueItemID: UUID,
          request: smithy.v2.CatalogueItemPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemPut(organizationID, catalogueItemID, request))

      override def catalogueItemArchivePut(
          organizationID: UUID,
          catalogueItemID: UUID,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemArchivePut(organizationID, catalogueItemID))

      override def catalogueItemVariantArchivePut(
          organizationID: UUID,
          catalogueItemID: UUID,
          catalogueItemVariantID: UUID,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.catalogueItemVariantArchivePut(organizationID, catalogueItemID, catalogueItemVariantID)
        )

      override def catalogueItemGet(
          organizationID: UUID,
          catalogueItemID: UUID,
      ): Task[smithy.v2.CatalogueItemGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemGet(organizationID, catalogueItemID))

      override def catalogueItemSummaryGet(
          organizationID: UUID,
          catalogueItemIDs: List[UUID],
      ): Task[smithy.v2.CatalogueItemSummaryGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemSummaryGet(organizationID, catalogueItemIDs))

      override def catalogueItemUniquenessGet(
          organizationID: UUID,
          name: Option[String],
          sku: Option[String],
      ): Task[smithy.v2.CatalogueItemUniquenessGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemUniquenessGet(organizationID, name, sku))
    }

  val local = ZLayer.derive[CatalogueServiceV2Impl].project[smithy.v2.CatalogueServiceV2[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
