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

  private val frSchema                  = Fragment.const(config.schema)
  private val frCatalogueItemTableName  = Fragment.const(config.catalogueItemTable)
  private val frCatalogueItemTable      = frSchema ++ fr0"." ++ frCatalogueItemTableName
  private val frCatalogueItemStatusType = frSchema ++ fr0".catalogue_item_status"

  private val frCatalogueItemInsertFields =
    fr"""
        |organization_id,
        |catalogue_item_id,
        |name,
        |unit,
        |price_amount,
        |price_currency,
        |image_original_s3_bucket_key,
        |image_normalized_s3_bucket_key,
        |image_original_file_name,
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
      fr0"${row.price.map(_.value.amount)}",
      fr0"${row.price.map(_.value.currency)}",
      fr0"${row.imageAsset.map(_.value.imageOriginalS3BucketKey)}",
      fr0"${row.imageAsset.map(_.value.imageNormalizedS3BucketKey)}",
      fr0"${row.imageAsset.map(_.value.imageOriginalFileName)}",
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
        |image_original_s3_bucket_key,
        |image_normalized_s3_bucket_key,
        |image_original_file_name,
        |status::text,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCatalogueItemSummarySelectFields =
    fr"""
        |catalogue_item_id,
        |name,
        |image_original_s3_bucket_key,
        |image_normalized_s3_bucket_key,
        |image_original_file_name,
        |status::text
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
      priceOptUpdate: Option[CatalogueItemPrice] = None,
      imageAssetOptUpdate: Option[CatalogueItemImageAsset] = None,
  ): TranzactIO[Option[CatalogueItemRow]] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      nameOptUpdate.map(v => fr"name = $v"),
      unitOptUpdate.map(v => fr"unit = $v"),
      priceOptUpdate.map(v => fr"price_amount = ${v.value.amount}"),
      priceOptUpdate.map(v => fr"price_currency = ${v.value.currency}"),
      imageAssetOptUpdate.map(v => fr"image_original_s3_bucket_key = ${v.value.imageOriginalS3BucketKey}"),
      imageAssetOptUpdate.map(v => fr"image_normalized_s3_bucket_key = ${v.value.imageNormalizedS3BucketKey}"),
      imageAssetOptUpdate.map(v => fr"image_original_file_name = ${v.value.imageOriginalFileName}"),
    ).flatten

    tzio {
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

  def getCatalogueItemSummaryRowsActive(organizationID: OrganizationID): TranzactIO[List[CatalogueItemSummaryRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSummarySelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          )

      q.query[CatalogueItemSummaryRow].to[List]
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
