package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.CatalogueRepository.InsertCatalogueItemInput
import io.mesazon.gateway.validation.service.CatalogueRequestValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

import java.util.UUID

object CatalogueService {

  private final class CatalogueServiceImpl(
      catalogueRequestValidator: CatalogueRequestValidator,
      catalogueRepository: CatalogueRepository,
      s3ClientOrganizationMedia: S3ClientOrganizationMedia,
  ) extends smithy.CatalogueService[ServiceTask] {

    /** HTTP POST /insert/catalogue-item */
    override def insertCatalogueItemPost(
        organizationID: UUID,
        insertCatalogueItemPostRequestSmithy: smithy.InsertCatalogueItemPostRequest,
    ): ServiceTask[Unit] = for {
      insertCatalogueItemPostRequest <- catalogueRequestValidator.validatedInsertCatalogueItemPostRequest(
        insertCatalogueItemPostRequestSmithy
      )
      _ <- catalogueRepository.insertCatalogueItem(
        OrganizationID(organizationID),
        InsertCatalogueItemInput(
          name = insertCatalogueItemPostRequest.name,
          unit = insertCatalogueItemPostRequest.unit,
          price = insertCatalogueItemPostRequest.price,
        ),
      )
    } yield ()

    /** HTTP POST /insert/catalogue-items */
    override def insertCatalogueItemsPost(
        organizationID: UUID,
        insertCatalogueItemsPostRequestSmithy: smithy.InsertCatalogueItemsPostRequest,
    ): ServiceTask[Unit] = for {
      insertCatalogueItemsPostRequest <- catalogueRequestValidator.validatedInsertCatalogueItemsPostRequest(
        insertCatalogueItemsPostRequestSmithy
      )
      _ <- catalogueRepository.insertCatalogueItems(
        OrganizationID(organizationID),
        insertCatalogueItemsPostRequest.catalogueItems.map(insertCatalogueItemPostRequest =>
          InsertCatalogueItemInput(
            name = insertCatalogueItemPostRequest.name,
            unit = insertCatalogueItemPostRequest.unit,
            price = insertCatalogueItemPostRequest.price,
          )
        ),
      )
    } yield ()

    /** HTTP PUT /update/catalogue-item */
    override def updateCatalogueItemPut(
        organizationID: UUID,
        updateCatalogueItemPutRequestSmithy: smithy.UpdateCatalogueItemPutRequest,
    ): ServiceTask[Unit] = for {
      updateCatalogueItemPutRequest <- catalogueRequestValidator.validatedUpdateCatalogueItemPutRequest(
        updateCatalogueItemPutRequestSmithy
      )
      _ <- catalogueRepository.updateCatalogueItem(
        organizationID = OrganizationID(organizationID),
        catalogueItemID = updateCatalogueItemPutRequest.catalogueItemID,
        nameOptUpdate = updateCatalogueItemPutRequest.name,
        unitOptUpdate = updateCatalogueItemPutRequest.unit,
        priceOptUpdate = updateCatalogueItemPutRequest.price,
      )
    } yield ()

    /** HTTP PUT /archive/catalogue-item */
    override def archiveCatalogueItemPut(
        organizationID: UUID,
        archiveCatalogueItemPutRequestSmithy: smithy.ArchiveCatalogueItemPutRequest,
    ): ServiceTask[Unit] =
      catalogueRepository
        .archiveCatalogueItem(
          OrganizationID(organizationID),
          CatalogueItemID(archiveCatalogueItemPutRequestSmithy.catalogueItemID),
        )
        .unit

    /** HTTP GET /get/catalogue-item/{catalogueItemID} */
    override def getCatalogueItemGet(
        organizationID: UUID,
        catalogueItemID: UUID,
    ): ServiceTask[smithy.GetCatalogueItemGetResponse] = for {
      catalogueItemRow <- catalogueRepository
        .getCatalogueItem(OrganizationID(organizationID), CatalogueItemID(catalogueItemID))
        .someOrFail(
          ServiceError.InternalServerError
            .UnexpectedError(s"Catalogue item not found for catalogueItemID: [$catalogueItemID]")
        )
      imageNormalizedUrlOpt <- ZIO.foreach(catalogueItemRow.imageAsset)(catalogueItemImageAsset =>
        s3ClientOrganizationMedia
          .genMediaUrl(catalogueItemImageAsset.value.imageNormalizedS3BucketKey.to[S3BucketKey])
          .map(_.value)
      )
    } yield smithy.GetCatalogueItemGetResponse(
      catalogueItemID = catalogueItemRow.catalogueItemID.value,
      name = catalogueItemRow.name.value,
      unit = catalogueItemRow.unit.value,
      price = catalogueItemRow.price.map(catalogueItemPrice =>
        smithy.CatalogueItemPriceRequest(
          amount = catalogueItemPrice.value.amount.value,
          currency = catalogueItemPrice.value.currency.value,
        )
      ),
      imageNormalizedUrl = imageNormalizedUrlOpt,
    )

    /** HTTP GET /get/catalogue-items */
    override def getCatalogueItemsGet(
        organizationID: UUID
    ): ServiceTask[smithy.GetCatalogueItemsGetResponse] = for {
      catalogueItemSummaryRows <- catalogueRepository.getCatalogueItemSummariesActive(OrganizationID(organizationID))
      catalogueItems           <- ZIO.foreach(catalogueItemSummaryRows)(catalogueItemSummaryRow =>
        ZIO
          .foreach(catalogueItemSummaryRow.imageAsset)(catalogueItemImageAsset =>
            s3ClientOrganizationMedia
              .genMediaUrl(catalogueItemImageAsset.value.imageNormalizedS3BucketKey.to[S3BucketKey])
              .map(_.value)
          )
          .map(imageNormalizedUrlOpt =>
            smithy.GetCatalogueItem(
              catalogueItemID = catalogueItemSummaryRow.catalogueItemID.value,
              name = catalogueItemSummaryRow.name.value,
              status = catalogueItemStatusFromDomainToSmithy(catalogueItemSummaryRow.status),
              imageNormalizedUrl = imageNormalizedUrlOpt,
            )
          )
      )
    } yield smithy.GetCatalogueItemsGetResponse(catalogueItems)
  }

  private def observed(service: smithy.CatalogueService[ServiceTask]): smithy.CatalogueService[Task] =
    new smithy.CatalogueService[Task] {

      /** HTTP POST /insert/catalogue-item */
      override def insertCatalogueItemPost(
          organizationID: UUID,
          insertCatalogueItemPostRequestSmithy: smithy.InsertCatalogueItemPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCatalogueItemPost(organizationID, insertCatalogueItemPostRequestSmithy)
        )

      /** HTTP POST /insert/catalogue-items */
      override def insertCatalogueItemsPost(
          organizationID: UUID,
          insertCatalogueItemsPostRequestSmithy: smithy.InsertCatalogueItemsPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.insertCatalogueItemsPost(organizationID, insertCatalogueItemsPostRequestSmithy)
        )

      /** HTTP PUT /update/catalogue-item */
      override def updateCatalogueItemPut(
          organizationID: UUID,
          updateCatalogueItemPutRequestSmithy: smithy.UpdateCatalogueItemPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.updateCatalogueItemPut(organizationID, updateCatalogueItemPutRequestSmithy)
        )

      /** HTTP PUT /archive/catalogue-item */
      override def archiveCatalogueItemPut(
          organizationID: UUID,
          archiveCatalogueItemPutRequestSmithy: smithy.ArchiveCatalogueItemPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(
          service.archiveCatalogueItemPut(organizationID, archiveCatalogueItemPutRequestSmithy)
        )

      /** HTTP GET /get/catalogue-item/{catalogueItemID} */
      override def getCatalogueItemGet(
          organizationID: UUID,
          catalogueItemID: UUID,
      ): Task[smithy.GetCatalogueItemGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCatalogueItemGet(organizationID, catalogueItemID))

      /** HTTP GET /get/catalogue-items */
      override def getCatalogueItemsGet(organizationID: UUID): Task[smithy.GetCatalogueItemsGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCatalogueItemsGet(organizationID))
    }

  val local = ZLayer.derive[CatalogueServiceImpl].project[smithy.CatalogueService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
