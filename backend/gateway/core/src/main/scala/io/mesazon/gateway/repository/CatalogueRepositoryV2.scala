package io.mesazon.gateway.repository

import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CatalogueRepositoryV2.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CatalogueQueries
import io.mesazon.generator.IDGenerator
import org.typelevel.doobie.Transactor
import zio.*

import java.time.Instant

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
      variants: NonEmptyChunk[InsertCatalogueItemVariantInput],
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

  private final class CatalogueRepositoryV2Impl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      catalogueQueries: CatalogueQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends CatalogueRepositoryV2 {

    override def insertCatalogueItem(
        organizationID: OrganizationID,
        insertCatalogueItemInput: InsertCatalogueItemInput,
    ): IO[ServiceError, CatalogueItemID] = for {
      instantNow                        <- timeProvider.instantNow
      catalogueItemID                   <- generateCatalogueItemID
      catalogueItemVariantIDsWithInputs <- ZIO.foreach(insertCatalogueItemInput.variants)(
        insertCatalogueItemVariantInput =>
          generateCatalogueItemVariantID.map(catalogueItemVariantID =>
            (
              catalogueItemVariantID = catalogueItemVariantID,
              insertCatalogueItemVariantInput = insertCatalogueItemVariantInput,
            )
          )
      )
      catalogueItemRowV2 = buildCatalogueItemRowV2(
        organizationID,
        catalogueItemID,
        insertCatalogueItemInput,
        instantNow,
      )
      catalogueItemVariantRows = catalogueItemVariantIDsWithInputs.map(catalogueItemVariantIDWithInput =>
        buildCatalogueItemVariantRow(
          organizationID,
          catalogueItemID,
          catalogueItemVariantIDWithInput.catalogueItemVariantID,
          catalogueItemVariantIDWithInput.insertCatalogueItemVariantInput,
          instantNow,
        )
      )
      _ <- database
        .transactionOrWiden(for {
          _ <- catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)
          _ <- catalogueQueries.insertCatalogueItemVariantRows(catalogueItemVariantRows.toList)
        } yield ())
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to insert catalogue item with ID: [$catalogueItemID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield catalogueItemID

    override def insertCatalogueItems(
        organizationID: OrganizationID,
        insertCatalogueItemInputs: NonEmptyChunk[InsertCatalogueItemInput],
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

    private val uniqueConstraintViolationMessage: PartialFunction[String, String] = {
      case "uq_catalogue_item_variant_sku" =>
        "A catalogue item variant with the given SKU already exists in this organization"
      case "uq_catalogue_item_name" => "A catalogue item with the given name already exists in this organization"
    }

    private def generateCatalogueItemID: IO[ServiceError, CatalogueItemID] =
      idGenerator.generateID
        .map(CatalogueItemID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError(s"Failed to construct catalogueItemID: [$e]")
            )
        )

    private def generateCatalogueItemVariantID: IO[ServiceError, CatalogueItemVariantID] =
      idGenerator.generateID
        .map(CatalogueItemVariantID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError(s"Failed to construct catalogueItemVariantID: [$e]")
            )
        )

    private def buildCatalogueItemRowV2(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        input: InsertCatalogueItemInput,
        instantNow: Instant,
    ): CatalogueItemRowV2 =
      CatalogueItemRowV2(
        organizationID,
        catalogueItemID,
        input.name,
        input.description,
        None,
        CatalogueItemStatus.Active,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
      )

    private def buildCatalogueItemVariantRow(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemVariantID: CatalogueItemVariantID,
        input: InsertCatalogueItemVariantInput,
        instantNow: Instant,
    ): CatalogueItemVariantRow =
      CatalogueItemVariantRow(
        organizationID,
        catalogueItemVariantID,
        catalogueItemID,
        input.sku,
        input.label,
        input.description,
        input.attributes,
        input.price,
        input.sellingUnit,
        input.status,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
      )
  }

  val live = ZLayer.derive[CatalogueRepositoryV2Impl].project[CatalogueRepositoryV2](identity)
}
