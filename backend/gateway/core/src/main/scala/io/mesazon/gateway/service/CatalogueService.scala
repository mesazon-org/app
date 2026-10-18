package io.mesazon.gateway.service

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.CatalogueRepository.*
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.validation.service.CatalogueRequestValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.scalaland.chimney.dsl.*
import zio.*

import java.util.UUID

object CatalogueService {

  private final class CatalogueServiceImpl(
      catalogueRequestValidator: CatalogueRequestValidator,
      catalogueRepository: CatalogueRepository,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
  ) extends smithy.CatalogueService[ServiceTask] {

    override def catalogueItemPost(
        organizationID: UUID,
        request: smithy.CatalogueItemPostRequest,
    ): ServiceTask[smithy.CatalogueItemPostResponse] =
      for {
        catalogueItemPostRequest <- catalogueRequestValidator.validatedCatalogueItemPostRequest(request)
        catalogueItemID          <- catalogueRepository.insertCatalogueItem(
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
      } yield smithy.CatalogueItemPostResponse(catalogueItemID.value)

    override def catalogueItemBatchPost(
        organizationID: UUID,
        request: smithy.CatalogueItemBatchPostRequest,
    ): ServiceTask[smithy.CatalogueItemBatchPostResponse] =
      for {
        catalogueItemBatchPostRequest <- catalogueRequestValidator.validatedCatalogueItemBatchPostRequest(request)
        catalogueItemIDs              <- catalogueRepository.insertCatalogueItems(
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
      } yield smithy.CatalogueItemBatchPostResponse(catalogueItemIDs.transformInto[List[UUID]])

    override def catalogueItemPut(
        organizationID: UUID,
        catalogueItemID: UUID,
        request: smithy.CatalogueItemPutRequest,
    ): ServiceTask[Unit] =
      for {
        catalogueItemPutRequest <- catalogueRequestValidator.validatedCatalogueItemPutRequest(request)
        _                       <- catalogueRepository.updateCatalogueItem(
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
      catalogueRepository.archiveCatalogueItem(
        OrganizationID(organizationID),
        CatalogueItemID(catalogueItemID),
      )

    override def catalogueItemVariantArchivePut(
        organizationID: UUID,
        catalogueItemID: UUID,
        catalogueItemVariantID: UUID,
    ): ServiceTask[Unit] =
      catalogueRepository.archiveCatalogueItemVariant(
        OrganizationID(organizationID),
        CatalogueItemID(catalogueItemID),
        CatalogueItemVariantID(catalogueItemVariantID),
      )

    override def catalogueItemGet(
        organizationID: UUID,
        catalogueItemID: UUID,
    ): ServiceTask[smithy.CatalogueItemGetResponse] =
      for {
        catalogueItemWithVariantsRow <- catalogueRepository
          .getCatalogueItemWithVariantsActive(
            OrganizationID(organizationID),
            CatalogueItemID(catalogueItemID),
          )
          .someOrFail(
            ServiceError.InternalServerError
              .UnexpectedError(s"Catalogue item not found for catalogueItemID: [$catalogueItemID]")
          )
        imageNormalizedUrlOpt <- ZIO.foreach(catalogueItemWithVariantsRow.catalogueItemRow.imageAsset)(
          catalogueItemImageAsset =>
            s3ClientOrganizationMedia
              .genMediaUrl(catalogueItemImageAsset.value.imageNormalizedS3BucketKey.to[S3BucketKey])
        )
      } yield catalogueItemWithVariantsRow.catalogueItemRow
        .into[smithy.CatalogueItemGetResponse]
        .withFieldConst(_.imageNormalizedUrl, imageNormalizedUrlOpt.map(_.value))
        .withFieldComputed(
          _.variants,
          _ =>
            catalogueItemWithVariantsRow.catalogueItemVariantRows.toList.map { catalogueItemVariantRow =>
              catalogueItemVariantRow
                .into[smithy.CatalogueItemVariant]
                .withFieldComputed(
                  _.price,
                  _.price.map(catalogueItemVariantPrice =>
                    catalogueItemVariantPrice.value
                      .into[smithy.CatalogueItemVariantPrice]
                      .enableInheritedAccessors
                      .withFieldComputed(
                        _.currency,
                        price => Currency.valueOf(price.currency.value).transformInto[smithy.Currency],
                      )
                      .transform
                  ),
                )
                .transform
            },
        )
        .transform

    override def catalogueItemSummaryGet(
        organizationID: UUID,
        catalogueItemIDs: List[UUID],
    ): ServiceTask[smithy.CatalogueItemSummaryGetResponse] =
      for {
        catalogueItemSummaryRows <- catalogueItemIDs match {
          case Nil => catalogueRepository.getAllCatalogueItemSummariesEnabled(OrganizationID(organizationID))
          case catalogueItemID :: remainingCatalogueItemIDs =>
            catalogueRepository.getCatalogueItemSummariesEnabled(
              OrganizationID(organizationID),
              NonEmptyChunk.fromIterable(
                CatalogueItemID(catalogueItemID),
                remainingCatalogueItemIDs.map(CatalogueItemID.apply),
              ),
            )
        }
        catalogueItemSummaries <- ZIO.foreach(catalogueItemSummaryRows) { catalogueItemSummaryRow =>
          ZIO
            .foreach(catalogueItemSummaryRow.imageAsset)(catalogueItemImageAsset =>
              s3ClientOrganizationMedia
                .genMediaUrl(catalogueItemImageAsset.value.imageNormalizedS3BucketKey.to[S3BucketKey])
            )
            .map(imageNormalizedUrlOpt =>
              catalogueItemSummaryRow
                .into[smithy.CatalogueItemSummary]
                .withFieldConst(_.imageNormalizedUrl, imageNormalizedUrlOpt.map(_.value))
                .withFieldComputed(
                  _.variants,
                  _ =>
                    catalogueItemSummaryRow.catalogueItemVariantSummaryRows.toList.map {
                      catalogueItemVariantSummaryRow =>
                        catalogueItemVariantSummaryRow
                          .into[smithy.CatalogueItemVariantSummary]
                          .withFieldComputed(
                            _.price,
                            _.price.map(catalogueItemVariantPrice =>
                              catalogueItemVariantPrice.value
                                .into[smithy.CatalogueItemVariantPrice]
                                .enableInheritedAccessors
                                .withFieldComputed(
                                  _.currency,
                                  price => Currency.valueOf(price.currency.value).transformInto[smithy.Currency],
                                )
                                .transform
                            ),
                          )
                          .transform
                    },
                )
                .transform
            )
        }
      } yield smithy.CatalogueItemSummaryGetResponse(catalogueItemSummaries)

    override def catalogueItemUniquenessGet(
        organizationID: UUID,
        name: Option[String],
        sku: Option[String],
    ): ServiceTask[smithy.CatalogueItemUniquenessGetResponse] =
      for {
        catalogueItemUniquenessGetRequest <- catalogueRequestValidator.validatedCatalogueItemUniquenessGetRequest(
          smithy.CatalogueItemUniquenessGetInput(organizationID, name, sku)
        )
        isNameUniqueOpt <- ZIO.foreach(catalogueItemUniquenessGetRequest.name)(catalogueItemName =>
          catalogueRepository.isUniqueCatalogueItemName(
            OrganizationID(organizationID),
            catalogueItemName,
          )
        )
        isSkuUniqueOpt <- ZIO.foreach(catalogueItemUniquenessGetRequest.sku)(catalogueItemVariantSku =>
          catalogueRepository.isUniqueCatalogueItemVariantSKU(
            OrganizationID(organizationID),
            catalogueItemVariantSku,
          )
        )
      } yield smithy.CatalogueItemUniquenessGetResponse(isNameUniqueOpt, isSkuUniqueOpt)
  }

  private def observed(service: smithy.CatalogueService[ServiceTask]): smithy.CatalogueService[Task] =
    new smithy.CatalogueService[Task] {
      override def catalogueItemPost(
          organizationID: UUID,
          request: smithy.CatalogueItemPostRequest,
      ): Task[smithy.CatalogueItemPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemPost(organizationID, request))

      override def catalogueItemBatchPost(
          organizationID: UUID,
          request: smithy.CatalogueItemBatchPostRequest,
      ): Task[smithy.CatalogueItemBatchPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemBatchPost(organizationID, request))

      override def catalogueItemPut(
          organizationID: UUID,
          catalogueItemID: UUID,
          request: smithy.CatalogueItemPutRequest,
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
      ): Task[smithy.CatalogueItemGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemGet(organizationID, catalogueItemID))

      override def catalogueItemSummaryGet(
          organizationID: UUID,
          catalogueItemIDs: List[UUID],
      ): Task[smithy.CatalogueItemSummaryGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemSummaryGet(organizationID, catalogueItemIDs))

      override def catalogueItemUniquenessGet(
          organizationID: UUID,
          name: Option[String],
          sku: Option[String],
      ): Task[smithy.CatalogueItemUniquenessGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.catalogueItemUniquenessGet(organizationID, name, sku))
    }

  val local = ZLayer.derive[CatalogueServiceImpl].project[smithy.CatalogueService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
