package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
import zio.*

final class CatalogueItemQueries(
    config: RepositoryConfig
) {

  private val frSchema = Fragment.const(config.schema)

  private val frCatalogueItemTableName = Fragment.const(config.catalogueItemTable)

  private val frCatalogueItemTable = frSchema ++ fr0"." ++ frCatalogueItemTableName

  // The `catalogue_item_status` enum lives in the config schema (created there by Flyway). The app
  // connection's search_path does not include that schema, so casts must qualify it — exactly as
  // the table above is qualified.
  private val frCatalogueItemStatusType = frSchema ++ fr0".catalogue_item_status"

  // `status` is a native `catalogue_item_status` enum. The generic `CatalogueItemStatus` codec
  // (derived from its string labels) binds/reads it as text, so writes cast the bound param
  // `?::<schema>.catalogue_item_status` (via `frCatalogueItemStatusType`) and selects read
  // `status::text` — any new query touching `status` must keep those casts. `price_amount` maps to
  // a `numeric` column: doobie's `Meta[BigDecimal]` (lifted onto `CatalogueItemPriceAmount` by the
  // generic `RefinedType.Mirror` given in `queries.scala`) round-trips it with no precision loss.

  private val frCatalogueItemInsertFields =
    fr"""
        |organization_id,
        |catalogue_item_id,
        |name,
        |unit,
        |price_amount,
        |price_currency,
        |photo_original_bucket_key,
        |photo_normalized_bucket_key,
        |photo_original_file_name,
        |status,
        |created_at,
        |updated_at
         """.stripMargin

  private def frCatalogueItemValues(row: CatalogueItemRow): Fragment =
    fr0"(" ++ List(
      fr0"${row.organizationID}",
      fr0"${row.catalogueItemID}",
      fr0"${row.name}",
      fr0"${row.unit}",
      fr0"${row.priceAmount}",
      fr0"${row.priceCurrency}",
      fr0"${row.photoOriginalBucketKey}",
      fr0"${row.photoNormalizedBucketKey}",
      fr0"${row.photoOriginalFileName}",
      fr0"${row.status}::" ++ frCatalogueItemStatusType,
      fr0"${row.createdAt}",
      fr0"${row.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  private val frCatalogueItemSelectFields =
    fr"""
        |organization_id,
        |catalogue_item_id,
        |name,
        |unit,
        |price_amount,
        |price_currency,
        |photo_original_bucket_key,
        |photo_normalized_bucket_key,
        |photo_original_file_name,
        |status::text,
        |created_at,
        |updated_at
         """.stripMargin

  private def insertRowWith(
      frTable: Fragment,
      frFields: Fragment,
      frValues: CatalogueItemRow => Fragment,
      row: CatalogueItemRow,
  ): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frFields ++ fr")" ++
          fr"VALUES" ++ frValues(row)

      q.update.run.void
    }

  private def insertRowsWith(
      frTable: Fragment,
      frFields: Fragment,
      frValues: CatalogueItemRow => Fragment,
      rows: List[CatalogueItemRow],
  ): TranzactIO[Unit] =
    NonEmptyList.fromList(rows).fold(ZIO.unit: TranzactIO[Unit]) { rowsNel =>
      tzio {
        val frAllValues = rowsNel.toList.map(frValues).intercalate(fr",")
        val q           =
          fr"INSERT INTO" ++ frTable ++
            fr"(" ++ frFields ++ fr")" ++
            fr"VALUES" ++ frAllValues

        q.update.run.void
      }
    }

  def insertCatalogueItemRow(catalogueItemRow: CatalogueItemRow): TranzactIO[Unit] =
    insertRowWith(frCatalogueItemTable, frCatalogueItemInsertFields, frCatalogueItemValues, catalogueItemRow)

  def insertCatalogueItemRows(catalogueItemRows: List[CatalogueItemRow]): TranzactIO[Unit] =
    insertRowsWith(frCatalogueItemTable, frCatalogueItemInsertFields, frCatalogueItemValues, catalogueItemRows)

  def updateCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
      nameOptUpdate: Option[CatalogueItemName] = None,
      unitOptUpdate: Option[CatalogueItemUnit] = None,
      priceAmountOptUpdate: Option[CatalogueItemPriceAmount] = None,
      priceCurrencyOptUpdate: Option[CatalogueItemPriceCurrency] = None,
  ): TranzactIO[Option[CatalogueItemRow]] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      nameOptUpdate.map(v => fr"name = $v"),
      unitOptUpdate.map(v => fr"unit = $v"),
      priceAmountOptUpdate.map(v => fr"price_amount = $v"),
      priceCurrencyOptUpdate.map(v => fr"price_currency = $v"),
    ).flatten

    tzio {
      // Only active items are editable: archived (or absent) rows match nothing, so the update is a
      // silent no-op (`.option` → None). Keeps the mandatory `::catalogue_item_status` cast.
      val q =
        fr"UPDATE" ++ frCatalogueItemTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          ) ++
          fr"RETURNING" ++ frCatalogueItemSelectFields

      q.query[CatalogueItemRow].option
    }
  }

  // Archive (soft-delete) a catalogue item: only an *active* one — a missing or already-archived
  // item matches nothing and returns `None` (a silent no-op for the caller). Removing a row from the
  // active set can never violate the partial `uq_catalogue_item_name` index. Keeps the mandatory
  // `::catalogue_item_status` cast.
  def archiveCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
  ): TranzactIO[Option[CatalogueItemID]] =
    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemTable ++
          set(
            fr0"status = ${CatalogueItemStatus.Archived}::" ++ frCatalogueItemStatusType,
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          ) ++
          fr"RETURNING catalogue_item_id"

      q.query[CatalogueItemID].option
    }

  def getCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[Option[CatalogueItemRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
          )

      q.query[CatalogueItemRow].option
    }

  def getActiveCatalogueItemRows(organizationID: OrganizationID): TranzactIO[List[CatalogueItemRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          )

      q.query[CatalogueItemRow].to[List]
    }

  // Testing
  def getAllCatalogueItemRowsTesting: TranzactIO[List[CatalogueItemRow]] =
    tzio {
      val q = fr"SELECT" ++ frCatalogueItemSelectFields ++ fr"FROM" ++ frCatalogueItemTable
      q.query[CatalogueItemRow].to[List]
    }
}

object CatalogueItemQueries {

  val live = ZLayer.derive[CatalogueItemQueries]
}
