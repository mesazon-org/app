package io.mesazon.gateway.repository

import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CatalogueRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CatalogueItemQueries
import io.mesazon.generator.IDGenerator
import org.typelevel.doobie.Transactor
import zio.*

import java.time.Instant

trait CatalogueRepository {

  def insertCatalogueItem(
      organizationID: OrganizationID,
      insertCatalogueItemInput: InsertCatalogueItemInput,
  ): IO[ServiceError, CatalogueItemID]

  def insertCatalogueItems(
      organizationID: OrganizationID,
      insertCatalogueItemInputs: List[InsertCatalogueItemInput],
  ): IO[ServiceError, List[CatalogueItemID]]

  def updateCatalogueItem(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      nameOptUpdate: Option[CatalogueItemName] = None,
      unitOptUpdate: Option[CatalogueItemUnit] = None,
      priceOptUpdate: Option[CatalogueItemPrice] = None,
      imageAssetOptUpdate: Option[CatalogueItemImageAsset] = None,
  ): IO[ServiceError, Option[CatalogueItemRow]]

  def archiveCatalogueItem(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): IO[ServiceError, Option[CatalogueItemID]]

  def getCatalogueItem(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): IO[ServiceError, Option[CatalogueItemRow]]

  def getCatalogueItemSummariesActive(
      organizationID: OrganizationID
  ): IO[ServiceError, List[CatalogueItemSummaryRow]]
}

object CatalogueRepository {

  case class InsertCatalogueItemInput(
      name: CatalogueItemName,
      unit: CatalogueItemUnit,
      price: Option[CatalogueItemPrice] = None,
  )

  private final class CatalogueRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      catalogueItemQueries: CatalogueItemQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends CatalogueRepository {

    override def insertCatalogueItem(
        organizationID: OrganizationID,
        insertCatalogueItemInput: InsertCatalogueItemInput,
    ): IO[ServiceError, CatalogueItemID] = for {
      instantNow      <- timeProvider.instantNow
      catalogueItemID <- generateCatalogueItemID
      catalogueItemRow = buildCatalogueItemRow(
        organizationID,
        catalogueItemID,
        insertCatalogueItemInput,
        instantNow,
      )
      _ <- database
        .transactionOrWiden(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to insert catalogue item with ID: [$catalogueItemID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield catalogueItemID

    override def insertCatalogueItems(
        organizationID: OrganizationID,
        insertCatalogueItemInputs: List[InsertCatalogueItemInput],
    ): IO[ServiceError, List[CatalogueItemID]] =
      ZIO
        .when(insertCatalogueItemInputs.nonEmpty) {
          for {
            instantNow                 <- timeProvider.instantNow
            catalogueItemIDsWithInputs <- ZIO.foreach(insertCatalogueItemInputs)(input =>
              generateCatalogueItemID.map(catalogueItemID => (catalogueItemID = catalogueItemID, input = input))
            )
            catalogueItemRows = catalogueItemIDsWithInputs.map(catalogueItemIDWithInput =>
              buildCatalogueItemRow(
                organizationID,
                catalogueItemIDWithInput.catalogueItemID,
                catalogueItemIDWithInput.input,
                instantNow,
              )
            )
            _ <- database
              .transactionOrWiden(catalogueItemQueries.insertCatalogueItemRows(catalogueItemRows))
              .mapError(
                catchUniqueConstraintViolation(
                  s"Failed to insert catalogue items for organization ID: [$organizationID]",
                  uniqueConstraintViolationMessage,
                )
              )
          } yield catalogueItemIDsWithInputs.map(_.catalogueItemID)
        }
        .map(_.getOrElse(Nil))

    override def updateCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        nameOptUpdate: Option[CatalogueItemName],
        unitOptUpdate: Option[CatalogueItemUnit],
        priceOptUpdate: Option[CatalogueItemPrice],
        imageAssetOptUpdate: Option[CatalogueItemImageAsset],
    ): IO[ServiceError, Option[CatalogueItemRow]] = for {
      instantNow              <- timeProvider.instantNow
      catalogueItemRowUpdated <- database
        .transactionOrWiden(
          catalogueItemQueries.updateCatalogueItemRow(
            organizationID,
            catalogueItemID,
            UpdatedAt(instantNow),
            nameOptUpdate,
            unitOptUpdate,
            priceOptUpdate,
            imageAssetOptUpdate,
          )
        )
        .mapError(
          catchUniqueConstraintViolation(
            s"Failed to update catalogue item with ID: [$catalogueItemID]",
            uniqueConstraintViolationMessage,
          )
        )
    } yield catalogueItemRowUpdated

    override def archiveCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
    ): IO[ServiceError, Option[CatalogueItemID]] = for {
      instantNow                 <- timeProvider.instantNow
      catalogueItemIDOptArchived <- database
        .transactionOrWiden(
          catalogueItemQueries.archiveCatalogueItemRow(organizationID, catalogueItemID, UpdatedAt(instantNow))
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to archive catalogue item with ID: [$catalogueItemID]",
            e,
          )
        )
    } yield catalogueItemIDOptArchived

    override def getCatalogueItem(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
    ): IO[ServiceError, Option[CatalogueItemRow]] =
      database
        .transactionOrWiden(catalogueItemQueries.getCatalogueItemRow(organizationID, catalogueItemID))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get catalogue item with ID: [$catalogueItemID]",
            e,
          )
        )

    override def getCatalogueItemSummariesActive(
        organizationID: OrganizationID
    ): IO[ServiceError, List[CatalogueItemSummaryRow]] =
      database
        .transactionOrWiden(catalogueItemQueries.getCatalogueItemSummaryRowsActive(organizationID))
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get catalogue items for organization ID: [$organizationID]",
            e,
          )
        )

    private val uniqueConstraintViolationMessage: PartialFunction[String, String] = { case "uq_catalogue_item_name" =>
      "A catalogue item with the given name already exists in this organization"
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

    private def buildCatalogueItemRow(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        input: InsertCatalogueItemInput,
        instantNow: Instant,
    ): CatalogueItemRow =
      CatalogueItemRow(
        organizationID,
        catalogueItemID,
        input.name,
        input.unit,
        input.price,
        None,
        CatalogueItemStatus.Active,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
      )
  }

  val live = ZLayer.derive[CatalogueRepositoryImpl].project[CatalogueRepository](identity)
}
