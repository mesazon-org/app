package io.mesazon.gateway.repository

import cats.data.NonEmptyList
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

  def getAllCatalogueItemSummariesEnabled(
      organizationID: OrganizationID
  ): IO[ServiceError, List[CatalogueItemSummaryRowV2]]

  def getCatalogueItemSummariesEnabled(
      organizationID: OrganizationID,
      catalogueItemIDs: NonEmptyChunk[CatalogueItemID],
  ): IO[ServiceError, List[CatalogueItemSummaryRowV2]]

  def isUniqueCatalogueItemName(
      organizationID: OrganizationID,
      name: CatalogueItemName,
  ): IO[ServiceError, Boolean]

  def isUniqueCatalogueItemVariantSKU(
      organizationID: OrganizationID,
      sku: CatalogueItemVariantSku,
  ): IO[ServiceError, Boolean]
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
    ): IO[ServiceError, NonEmptyChunk[CatalogueItemID]] = for {
      instantNow                 <- timeProvider.instantNow
      catalogueItemIDsWithInputs <- ZIO.foreach(insertCatalogueItemInputs)(insertCatalogueItemInput =>
        generateCatalogueItemID.map(catalogueItemID =>
          (catalogueItemID = catalogueItemID, insertCatalogueItemInput = insertCatalogueItemInput)
        )
      )
      catalogueItemRowsV2WithVariantRows <- ZIO.foreach(catalogueItemIDsWithInputs) { catalogueItemIDWithInput =>
        ZIO
          .foreach(catalogueItemIDWithInput.insertCatalogueItemInput.variants)(insertCatalogueItemVariantInput =>
            generateCatalogueItemVariantID.map(catalogueItemVariantID =>
              (
                catalogueItemVariantID = catalogueItemVariantID,
                insertCatalogueItemVariantInput = insertCatalogueItemVariantInput,
              )
            )
          )
          .map(catalogueItemVariantIDsWithInputs =>
            (
              catalogueItemRowV2 = buildCatalogueItemRowV2(
                organizationID,
                catalogueItemIDWithInput.catalogueItemID,
                catalogueItemIDWithInput.insertCatalogueItemInput,
                instantNow,
              ),
              catalogueItemVariantRows = catalogueItemVariantIDsWithInputs.map(catalogueItemVariantIDWithInput =>
                buildCatalogueItemVariantRow(
                  organizationID,
                  catalogueItemIDWithInput.catalogueItemID,
                  catalogueItemVariantIDWithInput.catalogueItemVariantID,
                  catalogueItemVariantIDWithInput.insertCatalogueItemVariantInput,
                  instantNow,
                )
              ),
            )
          )
      }
      catalogueItemRowsV2      = catalogueItemRowsV2WithVariantRows.map(_.catalogueItemRowV2)
      catalogueItemVariantRows = catalogueItemRowsV2WithVariantRows.flatMap(_.catalogueItemVariantRows)
      _ <- database
        .transactionOrWiden(for {
          _ <- catalogueQueries.insertCatalogueItemRowsV2(catalogueItemRowsV2.toList)
          _ <- catalogueQueries.insertCatalogueItemVariantRows(catalogueItemVariantRows.toList)
        } yield ())
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to insert catalogue items for organization ID: [$organizationID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield catalogueItemIDsWithInputs.map(_.catalogueItemID)

    override def updateCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        nameOptUpdate: Option[CatalogueItemName],
        descriptionOptUpdate: Option[Option[CatalogueItemDescription]],
        imageAssetOptUpdate: Option[CatalogueItemImageAsset],
        updateCatalogueItemVariantInputs: List[UpdateCatalogueItemVariantInput],
        insertCatalogueItemVariantInputs: List[InsertCatalogueItemVariantInput],
    ): IO[ServiceError, Unit] = for {
      instantNow <- timeProvider.instantNow
      updatedAt = UpdatedAt(instantNow)
      insertCatalogueItemVariantIDsWithInputs <- ZIO.foreach(insertCatalogueItemVariantInputs)(
        insertCatalogueItemVariantInput =>
          generateCatalogueItemVariantID.map(catalogueItemVariantID =>
            (
              catalogueItemVariantID = catalogueItemVariantID,
              insertCatalogueItemVariantInput = insertCatalogueItemVariantInput,
            )
          )
      )
      _ <- database.transactionOrWiden {
        for {
          affectedItemRows <- catalogueQueries.updateCatalogueItemRowV2(
            organizationID,
            catalogueItemID,
            updatedAt,
            nameOptUpdate,
            descriptionOptUpdate,
            imageAssetOptUpdate,
          )
          _ <-
            if (affectedItemRows == 0) ZIO.unit
            else
              for {
                updateCatalogueItemVariantInputsOptNonEmpty = NonEmptyList.fromList(updateCatalogueItemVariantInputs)
                catalogueItemVariantRowsOptEnabled <- ZIO.foreach(
                  updateCatalogueItemVariantInputsOptNonEmpty
                )(updateCatalogueItemVariantInputsNonEmpty =>
                  catalogueQueries.getCatalogueItemVariantRowsByIDs(
                    organizationID,
                    catalogueItemID,
                    updateCatalogueItemVariantInputsNonEmpty.map(_.catalogueItemVariantID),
                  )
                )
                _ <- catalogueItemVariantRowsOptEnabled match {
                  case None                                  => ZIO.unit
                  case Some(catalogueItemVariantRowsEnabled) =>
                    if (catalogueItemVariantRowsEnabled.size != updateCatalogueItemVariantInputs.size)
                      ZIO.fail(
                        ServiceError.InternalServerError.UnexpectedError(
                          s"Failed to update catalogue item with ID: [$catalogueItemID]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are disabled. Requested: [${updateCatalogueItemVariantInputs.map(_.catalogueItemVariantID)}], Found: [${catalogueItemVariantRowsEnabled}]"
                        )
                      )
                    else ZIO.unit
                }
                catalogueItemVariantRowsOptUpdated = mergeUpdateCatalogueItemVariantInputs(
                  updateCatalogueItemVariantInputsOptNonEmpty,
                  catalogueItemVariantRowsOptEnabled.getOrElse(Nil),
                  updatedAt,
                )
                _ <- ZIO.foreach(catalogueItemVariantRowsOptUpdated)(catalogueItemVariantRowsUpdated =>
                  catalogueQueries.updateCatalogueItemVariantRows(
                    organizationID,
                    catalogueItemID,
                    catalogueItemVariantRowsUpdated,
                  )
                )
                catalogueItemVariantRows = insertCatalogueItemVariantIDsWithInputs.map(
                  catalogueItemVariantIDWithInput =>
                    buildCatalogueItemVariantRow(
                      organizationID,
                      catalogueItemID,
                      catalogueItemVariantIDWithInput.catalogueItemVariantID,
                      catalogueItemVariantIDWithInput.insertCatalogueItemVariantInput,
                      instantNow,
                    )
                )
                _ <- catalogueQueries.insertCatalogueItemVariantRows(catalogueItemVariantRows)
              } yield ()
        } yield ()
      }.mapError {
        case s: ServiceError => s
        case throwable       =>
          catchUniqueConstraintViolation(
            s"Failed to update catalogue item with ID: [$catalogueItemID]",
            uniqueConstraintViolationMessage,
          )(throwable)
      }
    } yield ()

    override def archiveCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
    ): IO[ServiceError, Unit] = for {
      instantNow <- timeProvider.instantNow
      updatedAt = UpdatedAt(instantNow)
      _ <- database
        .transactionOrWiden(for {
          affectedItemRows <- catalogueQueries.archiveCatalogueItemRow(organizationID, catalogueItemID, updatedAt)
          _                <-
            if (affectedItemRows == 0) ZIO.unit
            else
              catalogueQueries.archiveCatalogueItemVariantRowsByCatalogueItemID(
                organizationID,
                catalogueItemID,
                updatedAt,
              )
        } yield ())
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to archive catalogue item with ID: [$catalogueItemID]",
            e,
          )
        )
    } yield ()

    override def archiveCatalogueItemVariant(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemVariantID: CatalogueItemVariantID,
    ): IO[ServiceError, Unit] = for {
      instantNow <- timeProvider.instantNow
      updatedAt = UpdatedAt(instantNow)
      _ <- database.transactionOrWiden {
        for {
          isCatalogueItemRowLocked <- catalogueQueries.lockCatalogueItemRow(organizationID, catalogueItemID)
          _                        <-
            if (!isCatalogueItemRowLocked) ZIO.unit
            else
              for {
                catalogueItemVariantIDsEnabled <- catalogueQueries.getCatalogueItemVariantIDsEnabled(
                  organizationID,
                  catalogueItemID,
                )
                _ <-
                  if (!catalogueItemVariantIDsEnabled.contains(catalogueItemVariantID)) ZIO.unit
                  else if (catalogueItemVariantIDsEnabled == List(catalogueItemVariantID))
                    ZIO.fail(
                      ServiceError.ForbiddenError.InvalidAction(
                        "archiveCatalogueItemVariant",
                        s"Catalogue item variant [$catalogueItemVariantID] is the last enabled variant of catalogue item [$catalogueItemID]; archive the catalogue item instead",
                      )
                    )
                  else
                    catalogueQueries.archiveCatalogueItemVariantRow(
                      organizationID,
                      catalogueItemID,
                      catalogueItemVariantID,
                      updatedAt,
                    )
              } yield ()
        } yield ()
      }.mapError {
        case s: ServiceError => s
        case throwable       =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to archive catalogue item variant with ID: [$catalogueItemVariantID]",
            throwable,
          )
      }
    } yield ()

    override def getCatalogueItemWithVariantsActive(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
    ): IO[ServiceError, Option[CatalogueItemWithVariantsRow]] =
      database
        .transactionOrWiden(catalogueQueries.getCatalogueItemWithVariantsRowEnabled(organizationID, catalogueItemID))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get catalogue item with ID: [$catalogueItemID]",
            e,
          )
        )

    override def getAllCatalogueItemSummariesEnabled(
        organizationID: OrganizationID
    ): IO[ServiceError, List[CatalogueItemSummaryRowV2]] =
      database
        .transactionOrWiden(catalogueQueries.getAllCatalogueItemSummaryRowsV2Enabled(organizationID))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get catalogue item summaries for organization ID: [$organizationID]",
            e,
          )
        )

    override def getCatalogueItemSummariesEnabled(
        organizationID: OrganizationID,
        catalogueItemIDs: NonEmptyChunk[CatalogueItemID],
    ): IO[ServiceError, List[CatalogueItemSummaryRowV2]] =
      database
        .transactionOrWiden(
          catalogueQueries.getCatalogueItemSummaryRowsV2EnabledByIDs(organizationID, catalogueItemIDs)
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get catalogue item summaries for organization ID: [$organizationID], catalogue item IDs: [${catalogueItemIDs.mkString(", ")}]",
            e,
          )
        )

    override def isUniqueCatalogueItemName(
        organizationID: OrganizationID,
        name: CatalogueItemName,
    ): IO[ServiceError, Boolean] =
      database
        .transactionOrWiden(catalogueQueries.isUniqueCatalogueItemName(organizationID, name))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to check name uniqueness for organizationID: [$organizationID], name: [$name]",
            e,
          )
        )

    override def isUniqueCatalogueItemVariantSKU(
        organizationID: OrganizationID,
        sku: CatalogueItemVariantSku,
    ): IO[ServiceError, Boolean] =
      database
        .transactionOrWiden(catalogueQueries.isUniqueCatalogueItemVariantSKU(organizationID, sku))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to check SKU uniqueness for organizationID: [$organizationID], sku: [$sku]",
            e,
          )
        )

    private val uniqueConstraintViolationMessage: PartialFunction[String, String] = {
      case "uq_catalogue_item_variant_sku" =>
        "A catalogue item variant with the given SKU already exists in this organization"
      case "uq_catalogue_item_name" => "A catalogue item with the given name already exists in this organization"
    }

    private def mergeUpdateCatalogueItemVariantInputs(
        updateCatalogueItemVariantInputs: Option[NonEmptyList[UpdateCatalogueItemVariantInput]],
        catalogueItemVariantRowsExisting: List[CatalogueItemVariantRow],
        updatedAt: UpdatedAt,
    ): Option[NonEmptyList[CatalogueItemVariantRow]] =
      updateCatalogueItemVariantInputs
        .flatMap(updateCatalogueItemVariantInputsNonEmpty =>
          updateCatalogueItemVariantInputsNonEmpty.traverse { updateCatalogueItemVariantInput =>
            val catalogueItemVariantRowOptExisting = catalogueItemVariantRowsExisting.find(
              _.catalogueItemVariantID == updateCatalogueItemVariantInput.catalogueItemVariantID
            )
            catalogueItemVariantRowOptExisting.map { catalogueItemVariantRowExisting =>
              catalogueItemVariantRowExisting.copy(
                sku = updateCatalogueItemVariantInput.sku.getOrElse(catalogueItemVariantRowExisting.sku),
                label = updateCatalogueItemVariantInput.label.getOrElse(catalogueItemVariantRowExisting.label),
                description =
                  updateCatalogueItemVariantInput.description.getOrElse(catalogueItemVariantRowExisting.description),
                attributes =
                  updateCatalogueItemVariantInput.attributes.getOrElse(catalogueItemVariantRowExisting.attributes),
                price = updateCatalogueItemVariantInput.price.getOrElse(catalogueItemVariantRowExisting.price),
                sellingUnit =
                  updateCatalogueItemVariantInput.sellingUnit.getOrElse(catalogueItemVariantRowExisting.sellingUnit),
                status = updateCatalogueItemVariantInput.status.getOrElse(catalogueItemVariantRowExisting.status),
                updatedAt = updatedAt,
              )
            }
          }
        )

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
