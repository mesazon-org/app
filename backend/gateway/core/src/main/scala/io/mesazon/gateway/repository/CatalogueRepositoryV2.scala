package io.mesazon.gateway.repository

import cats.data.NonEmptyList
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CatalogueRepositoryV2.*
import io.mesazon.gateway.repository.domain.*
import zio.*

// Temporary staging name; renamed to CatalogueRepository at cutover. Do not copy the V2 convention elsewhere.
trait CatalogueRepositoryV2 {

  def insertCatalogueItem(
      organizationID: OrganizationID,
      insertCatalogueItemInput: InsertCatalogueItemInput,
  ): IO[ServiceError, CatalogueItemID]

  def insertCatalogueItems(
      organizationID: OrganizationID,
      insertCatalogueItemInputs: NonEmptyChunk[InsertCatalogueItemInput],
  ): IO[ServiceError, NonEmptyChunk[CatalogueItemID]]

  def updateCatalogueItem(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      nameOptUpdate: Option[CatalogueItemName] = None,
      descriptionOptUpdate: Option[Option[CatalogueItemDescription]] = None,
      imageAssetOptUpdate: Option[CatalogueItemImageAsset] = None,
      updateCatalogueItemVariantInputs: List[UpdateCatalogueItemVariantInput],
      insertCatalogueItemVariantInputs: List[InsertCatalogueItemVariantInput],
  ): IO[ServiceError, Unit]

  def archiveCatalogueItem(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): IO[ServiceError, Unit]

  def archiveCatalogueItemVariant(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemVariantID: CatalogueItemVariantID,
  ): IO[ServiceError, Unit]

  def getCatalogueItemWithVariantsActive(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): IO[ServiceError, Option[CatalogueItemWithVariantsRow]]

  def getAllCatalogueItemSummariesActive(
      organizationID: OrganizationID
  ): IO[ServiceError, List[CatalogueItemSummaryRowV2]]

  def getCatalogueItemSummariesActive(
      organizationID: OrganizationID,
      catalogueItemIDs: NonEmptyChunk[CatalogueItemID],
  ): IO[ServiceError, List[CatalogueItemSummaryRowV2]]
}

object CatalogueRepositoryV2 {

  case class InsertCatalogueItemInput(
      name: CatalogueItemName,
      description: Option[CatalogueItemDescription],
      variants: NonEmptyList[InsertCatalogueItemVariantInput],
  )

  case class InsertCatalogueItemVariantInput(
      sku: Option[CatalogueItemVariantSku],
      label: Option[CatalogueItemVariantLabel],
      description: Option[CatalogueItemVariantDescription],
      attributes: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
      price: Option[CatalogueItemVariantPrice],
      sellingUnit: CatalogueItemVariantSaleUnit,
      status: CatalogueItemVariantStatus,
  )

  case class UpdateCatalogueItemVariantInput(
      catalogueItemVariantID: CatalogueItemVariantID,
      sku: Option[Option[CatalogueItemVariantSku]],
      label: Option[Option[CatalogueItemVariantLabel]],
      description: Option[Option[CatalogueItemVariantDescription]],
      attributes: Option[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]],
      price: Option[Option[CatalogueItemVariantPrice]],
      sellingUnit: Option[CatalogueItemVariantSaleUnit],
      status: Option[CatalogueItemVariantStatus],
  )

  private final class CatalogueRepositoryV2Impl extends CatalogueRepositoryV2 {

    override def insertCatalogueItem(
        organizationID: OrganizationID,
        insertCatalogueItemInput: InsertCatalogueItemInput,
    ): IO[ServiceError, CatalogueItemID] =
      ZIO.die(new NotImplementedError("insertCatalogueItem"))

    override def insertCatalogueItems(
        organizationID: OrganizationID,
        insertCatalogueItemInputs: List[InsertCatalogueItemInput],
    ): IO[ServiceError, NonEmptyChunk[CatalogueItemID]] =
      ZIO.die(new NotImplementedError("insertCatalogueItems"))

    override def updateCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        nameOptUpdate: Option[CatalogueItemName],
        descriptionOptUpdate: Option[Option[CatalogueItemDescription]],
        imageAssetOptUpdate: Option[CatalogueItemImageAsset],
        updateCatalogueItemVariantInputs: List[UpdateCatalogueItemVariantInput],
        insertCatalogueItemVariantInputs: List[InsertCatalogueItemVariantInput],
    ): IO[ServiceError, Unit] =
      ZIO.die(new NotImplementedError("updateCatalogueItem"))

    override def archiveCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
    ): IO[ServiceError, Unit] =
      ZIO.die(new NotImplementedError("archiveCatalogueItem"))

    override def archiveCatalogueItemVariant(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemVariantID: CatalogueItemVariantID,
    ): IO[ServiceError, Unit] =
      ZIO.die(new NotImplementedError("archiveCatalogueItemVariant"))

    override def getCatalogueItemWithVariantsActive(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
    ): IO[ServiceError, Option[CatalogueItemWithVariantsRow]] =
      ZIO.die(new NotImplementedError("getCatalogueItemWithVariantsActive"))

    override def getAllCatalogueItemSummariesActive(
        organizationID: OrganizationID
    ): IO[ServiceError, List[CatalogueItemSummaryRowV2]] =
      ZIO.die(new NotImplementedError("getAllCatalogueItemSummariesActive"))

    override def getCatalogueItemSummariesActive(
        organizationID: OrganizationID,
        catalogueItemIDs: NonEmptyChunk[CatalogueItemID],
    ): IO[ServiceError, List[CatalogueItemSummaryRowV2]] =
      ZIO.die(new NotImplementedError("getCatalogueItemSummariesActive"))
  }

  val live = ZLayer.derive[CatalogueRepositoryV2Impl].project[CatalogueRepositoryV2](identity)
}
