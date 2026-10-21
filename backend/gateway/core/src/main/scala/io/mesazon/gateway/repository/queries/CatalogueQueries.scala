package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import io.github.gaelrenoux.tranzactio.doobie.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CatalogueQueries.*
import io.scalaland.chimney.dsl.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
import zio.*

final class CatalogueQueries(
    config: RepositoryConfig
) {

  private given catalogueItemVariantAttributesCodec
      : JsonValueCodec[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] =
    JsonCodecMaker.make
  private given catalogueItemVariantSaleUnitCodec: JsonValueCodec[CatalogueItemVariantSaleUnit] =
    JsonCodecMaker.make

  private given catalogueItemVariantAttributesMeta
      : Meta[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] = jsonbMeta
  private given catalogueItemVariantSaleUnitMeta: Meta[CatalogueItemVariantSaleUnit]     = jsonbMeta

  private val frSchema = Fragment.const(config.schema)

  private val frCatalogueItemTableName        = Fragment.const(config.catalogueItemTable)
  private val frCatalogueItemVariantTableName = Fragment.const(config.catalogueItemVariantTable)

  private val frCatalogueItemTable        = frSchema ++ fr0"." ++ frCatalogueItemTableName
  private val frCatalogueItemVariantTable = frSchema ++ fr0"." ++ frCatalogueItemVariantTableName

  private val frCatalogueItemStatusType        = frSchema ++ fr0".catalogue_item_status"
  private val frCatalogueItemVariantStatusType = frSchema ++ fr0".catalogue_item_variant_status"

  private val frCatalogueItemInsertFields =
    fr"""
        |organization_id,
        |catalogue_item_id,
        |name,
        |description,
        |image_original_s3_bucket_key,
        |image_normalized_s3_bucket_key,
        |image_original_file_name,
        |status,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private def frCatalogueItemValues(row: CatalogueItemRow): Fragment =
    fr0"(" ++ List(
      fr0"${row.organizationID}",
      fr0"${row.catalogueItemID}",
      fr0"${row.name}",
      fr0"${row.description}",
      fr0"${row.imageAsset.map(_.value.imageOriginalS3BucketKey)}",
      fr0"${row.imageAsset.map(_.value.imageNormalizedS3BucketKey)}",
      fr0"${row.imageAsset.map(_.value.imageOriginalFileName)}",
      fr0"${row.status}::" ++ frCatalogueItemStatusType,
      fr0"${row.isArchived}",
      fr0"${row.createdAt}",
      fr0"${row.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  private val frCatalogueItemSelectFields =
    fr"""
        |organization_id,
        |catalogue_item_id,
        |name,
        |description,
        |image_original_s3_bucket_key,
        |image_normalized_s3_bucket_key,
        |image_original_file_name,
        |status::text,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCatalogueItemVariantInsertFields =
    fr"""
        |organization_id,
        |catalogue_item_variant_id,
        |catalogue_item_id,
        |sku,
        |label,
        |description,
        |attributes,
        |price_amount,
        |price_currency,
        |selling_unit,
        |status,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private def frCatalogueItemVariantValues(row: CatalogueItemVariantRow): Fragment =
    fr0"(" ++ List(
      fr0"${row.organizationID}",
      fr0"${row.catalogueItemVariantID}",
      fr0"${row.catalogueItemID}",
      fr0"${row.sku}",
      fr0"${row.label}",
      fr0"${row.description}",
      fr0"${row.attributes}",
      fr0"${row.price.map(_.value.amount)}",
      fr0"${row.price.map(_.value.currency)}",
      fr0"${row.sellingUnit}",
      fr0"${row.status}::" ++ frCatalogueItemVariantStatusType,
      fr0"${row.isArchived}",
      fr0"${row.createdAt}",
      fr0"${row.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  private val frCatalogueItemVariantSelectFields =
    fr"""
        |organization_id,
        |catalogue_item_variant_id,
        |catalogue_item_id,
        |sku,
        |label,
        |description,
        |attributes,
        |price_amount,
        |price_currency,
        |selling_unit,
        |status::text,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCatalogueItemWithVariantJoinedSelectFields =
    fr"""
        |i.organization_id,
        |i.catalogue_item_id,
        |i.name,
        |i.description,
        |i.image_original_s3_bucket_key,
        |i.image_normalized_s3_bucket_key,
        |i.image_original_file_name,
        |i.status::text,
        |i.is_archived,
        |i.created_at,
        |i.updated_at,
        |v.catalogue_item_variant_id,
        |v.sku,
        |v.label,
        |v.description,
        |v.attributes,
        |v.price_amount,
        |v.price_currency,
        |v.selling_unit,
        |v.status::text,
        |v.is_archived,
        |v.created_at,
        |v.updated_at
         """.stripMargin

  private val frCatalogueItemSummaryWithVariantSummaryJoinedSelectFields =
    fr"""
        |i.catalogue_item_id,
        |i.name,
        |i.image_original_s3_bucket_key,
        |i.image_normalized_s3_bucket_key,
        |i.image_original_file_name,
        |i.status::text,
        |v.catalogue_item_variant_id,
        |v.sku,
        |v.label,
        |v.price_amount,
        |v.price_currency,
        |v.selling_unit,
        |v.status::text
         """.stripMargin

  private def insertRowWith[Row](
      frTable: Fragment,
      frFields: Fragment,
      frValues: Row => Fragment,
      row: Row,
  ): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frFields ++ fr")" ++
          fr"VALUES" ++ frValues(row)

      q.update.run.void
    }

  private def insertRowsWith[Row](
      frTable: Fragment,
      frFields: Fragment,
      frValues: Row => Fragment,
      rows: List[Row],
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

  def insertCatalogueItemVariantRows(catalogueItemVariantRows: List[CatalogueItemVariantRow]): TranzactIO[Unit] =
    insertRowsWith(
      frCatalogueItemVariantTable,
      frCatalogueItemVariantInsertFields,
      frCatalogueItemVariantValues,
      catalogueItemVariantRows,
    )

  def updateCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
      nameOptUpdate: Option[CatalogueItemName] = None,
      descriptionOptUpdate: Option[Option[CatalogueItemDescription]] = None,
      imageAssetOptUpdate: Option[CatalogueItemImageAsset] = None,
      statusOptUpdate: Option[CatalogueItemStatus] = None,
  ): TranzactIO[Int] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      nameOptUpdate.map(v => fr"name = $v"),
      descriptionOptUpdate.map(v => fr"description = $v"),
      imageAssetOptUpdate.map(v => fr"image_original_s3_bucket_key = ${v.value.imageOriginalS3BucketKey}"),
      imageAssetOptUpdate.map(v => fr"image_normalized_s3_bucket_key = ${v.value.imageNormalizedS3BucketKey}"),
      imageAssetOptUpdate.map(v => fr"image_original_file_name = ${v.value.imageOriginalFileName}"),
      statusOptUpdate.map(v => fr0"status = $v::" ++ frCatalogueItemStatusType ++ fr0" "),
    ).flatten

    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"is_archived = false",
          )

      q.update.run
    }
  }

  def archiveCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
  ): TranzactIO[Int] =
    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemTable ++
          set(
            fr"is_archived = true",
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"is_archived = false",
          )

      q.update.run
    }

  def getCatalogueItemVariantRowsByIDs(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemVariantIDs: NonEmptyList[CatalogueItemVariantID],
  ): TranzactIO[List[CatalogueItemVariantRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemVariantSelectFields ++
          fr"FROM" ++ frCatalogueItemVariantTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            in(fr"catalogue_item_variant_id", catalogueItemVariantIDs),
            fr"is_archived = false",
          )

      q.query[CatalogueItemVariantRow].to[List]
    }

  private val frCatalogueItemVariantUpdateValueColumns =
    fr"""
        |catalogue_item_variant_id,
        |sku,
        |label,
        |description,
        |attributes,
        |price_amount,
        |price_currency,
        |selling_unit,
        |status,
        |updated_at
         """.stripMargin

  private def frCatalogueItemVariantUpdateValues(row: CatalogueItemVariantRow): Fragment =
    fr0"(" ++ List(
      fr0"${row.catalogueItemVariantID}::uuid",
      fr0"${row.sku}::text",
      fr0"${row.label}::text",
      fr0"${row.description}::text",
      fr0"${row.attributes}::jsonb",
      fr0"${row.price.map(_.value.amount)}::numeric",
      fr0"${row.price.map(_.value.currency)}::text",
      fr0"${row.sellingUnit}::jsonb",
      fr0"${row.status}::" ++ frCatalogueItemVariantStatusType,
      fr0"${row.updatedAt}::timestamptz",
    ).intercalate(fr",") ++ fr0")"

  def updateCatalogueItemVariantRows(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemVariantRows: NonEmptyList[CatalogueItemVariantRow],
  ): TranzactIO[Unit] =
    tzio {
      val frAllValues = catalogueItemVariantRows.map(frCatalogueItemVariantUpdateValues).intercalate(fr",")

      val q =
        fr"UPDATE" ++ frCatalogueItemVariantTable ++ fr"AS t" ++
          fr"""
                |SET
                |sku = v.sku,
                |label = v.label,
                |description = v.description,
                |attributes = v.attributes,
                |price_amount = v.price_amount,
                |price_currency = v.price_currency,
                |selling_unit = v.selling_unit,
                |status = v.status,
                |updated_at = v.updated_at
                 """.stripMargin ++
          fr"FROM (VALUES" ++ frAllValues ++ fr") AS v (" ++ frCatalogueItemVariantUpdateValueColumns ++ fr")" ++
          whereAnd(
            fr"t.catalogue_item_variant_id = v.catalogue_item_variant_id",
            fr"t.organization_id = $organizationID",
            fr"t.catalogue_item_id = $catalogueItemID",
            fr"t.is_archived = false",
          )

      q.update.run.void
    }

  def archiveCatalogueItemVariantRowsByCatalogueItemID(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemVariantTable ++
          set(
            fr"is_archived = true",
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"is_archived = false",
          )

      q.update.run.void
    }

  def lockCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[Boolean] =
    tzio {
      val q =
        fr"SELECT catalogue_item_id FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"is_archived = false",
          ) ++
          fr"FOR NO KEY UPDATE"

      q.query[CatalogueItemID].option.map(_.isDefined)
    }

  def getCatalogueItemVariantIDs(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[List[CatalogueItemVariantID]] =
    tzio {
      val q =
        fr"SELECT catalogue_item_variant_id FROM" ++ frCatalogueItemVariantTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"is_archived = false",
          )

      q.query[CatalogueItemVariantID].to[List]
    }

  def archiveCatalogueItemVariantRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemVariantID: CatalogueItemVariantID,
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemVariantTable ++
          set(
            fr"is_archived = true",
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"catalogue_item_variant_id = $catalogueItemVariantID",
            fr"is_archived = false",
          )

      q.update.run.void
    }

  def getCatalogueItemWithVariantsRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[Option[CatalogueItemWithVariantsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemWithVariantJoinedSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++ fr"AS i" ++
          fr"INNER JOIN" ++ frCatalogueItemVariantTable ++ fr"AS v" ++
          fr"ON v.organization_id = i.organization_id" ++
          fr"AND v.catalogue_item_id = i.catalogue_item_id" ++
          fr"AND v.is_archived = false" ++
          whereAnd(
            fr"i.organization_id = $organizationID",
            fr"i.catalogue_item_id = $catalogueItemID",
            fr"i.is_archived = false",
          ) ++
          fr"ORDER BY v.created_at ASC, v.catalogue_item_variant_id ASC"

      q.query[CatalogueItemWithVariantJoinedRow]
        .to[List]
        .map(catalogueItemWithVariantJoinedRows =>
          NonEmptyChunk
            .fromIterableOption(catalogueItemWithVariantJoinedRows)
            .map(catalogueItemWithVariantJoinedRowsNonEmpty =>
              CatalogueItemWithVariantsRow(
                catalogueItemWithVariantJoinedRowsNonEmpty.head.catalogueItemRow,
                catalogueItemWithVariantJoinedRowsNonEmpty.map(
                  _.into[CatalogueItemVariantRow]
                    .withFieldConst(_.organizationID, organizationID)
                    .withFieldConst(_.catalogueItemID, catalogueItemID)
                    .transform
                ),
              )
            )
        )
    }

  def getAllCatalogueItemSummaryRows(
      organizationID: OrganizationID
  ): TranzactIO[List[CatalogueItemSummaryRow]] =
    getCatalogueItemSummaryRowsWhere(organizationID, Nil)

  def getCatalogueItemSummaryRowsByIDs(
      organizationID: OrganizationID,
      catalogueItemIDs: NonEmptyChunk[CatalogueItemID],
  ): TranzactIO[List[CatalogueItemSummaryRow]] =
    getCatalogueItemSummaryRowsWhere(
      organizationID,
      List(in(fr"i.catalogue_item_id", NonEmptyList(catalogueItemIDs.head, catalogueItemIDs.tail.toList))),
    )

  private def getCatalogueItemSummaryRowsWhere(
      organizationID: OrganizationID,
      frCatalogueItemConditions: List[Fragment],
  ): TranzactIO[List[CatalogueItemSummaryRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSummaryWithVariantSummaryJoinedSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++ fr"AS i" ++
          fr"INNER JOIN" ++ frCatalogueItemVariantTable ++ fr"AS v" ++
          fr"ON v.organization_id = i.organization_id" ++
          fr"AND v.catalogue_item_id = i.catalogue_item_id" ++
          fr"AND v.is_archived = false" ++
          whereAnd(
            fr"i.organization_id = $organizationID",
            fr"i.is_archived = false" :: frCatalogueItemConditions*
          ) ++
          fr"ORDER BY i.created_at ASC, i.catalogue_item_id ASC, v.created_at ASC, v.catalogue_item_variant_id ASC"

      q.query[CatalogueItemSummaryWithVariantSummaryJoinedRow]
        .to[List]
        .map { catalogueItemSummaryWithVariantSummaryJoinedRows =>
          val catalogueItemIDs = catalogueItemSummaryWithVariantSummaryJoinedRows.map(_.catalogueItemID).distinct
          val catalogueItemSummaryWithVariantSummaryJoinedRowsByCatalogueItemID =
            catalogueItemSummaryWithVariantSummaryJoinedRows.groupBy(_.catalogueItemID)

          catalogueItemIDs.flatMap(catalogueItemID =>
            catalogueItemSummaryWithVariantSummaryJoinedRowsByCatalogueItemID
              .get(catalogueItemID)
              .flatMap(NonEmptyChunk.fromIterableOption)
              .map(catalogueItemSummaryWithVariantSummaryJoinedRowsNonEmpty =>
                catalogueItemSummaryWithVariantSummaryJoinedRowsNonEmpty.head
                  .into[CatalogueItemSummaryRow]
                  .withFieldConst(
                    _.catalogueItemVariantSummaryRows,
                    catalogueItemSummaryWithVariantSummaryJoinedRowsNonEmpty.map(_.catalogueItemVariantSummaryRow),
                  )
                  .transform
              )
          )
        }
    }

  private def isUniqueWith(
      frTable: Fragment,
      organizationID: OrganizationID,
      frFieldPredicate: Fragment,
      frArchivePredicate: Fragment,
  ): TranzactIO[Boolean] =
    tzio {
      val q =
        fr"SELECT 1 FROM" ++ frTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            frFieldPredicate,
            frArchivePredicate,
          )

      q.query[Int].option.map(_.isEmpty)
    }

  def isUniqueCatalogueItemName(organizationID: OrganizationID, name: CatalogueItemName): TranzactIO[Boolean] =
    isUniqueWith(
      frCatalogueItemTable,
      organizationID,
      fr"name = $name",
      fr"is_archived = false",
    )

  def isUniqueCatalogueItemVariantSKU(
      organizationID: OrganizationID,
      sku: CatalogueItemVariantSku,
  ): TranzactIO[Boolean] =
    isUniqueWith(
      frCatalogueItemVariantTable,
      organizationID,
      fr"lower(sku) = lower($sku)",
      fr"is_archived = false",
    )

  // Testing
  def getAllCatalogueItemRowsTesting: TranzactIO[List[CatalogueItemRow]] =
    tzio {
      val q = fr"SELECT" ++ frCatalogueItemSelectFields ++ fr"FROM" ++ frCatalogueItemTable
      q.query[CatalogueItemRow].to[List]
    }

  def getAllCatalogueItemVariantRowsTesting: TranzactIO[List[CatalogueItemVariantRow]] =
    tzio {
      val q = fr"SELECT" ++ frCatalogueItemVariantSelectFields ++ fr"FROM" ++ frCatalogueItemVariantTable
      q.query[CatalogueItemVariantRow].to[List]
    }

  def getAllCatalogueItemWithVariantsRowsTesting: TranzactIO[List[CatalogueItemWithVariantsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemWithVariantJoinedSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++ fr"AS i" ++
          fr"INNER JOIN" ++ frCatalogueItemVariantTable ++ fr"AS v" ++
          fr"ON v.organization_id = i.organization_id" ++
          fr"AND v.catalogue_item_id = i.catalogue_item_id" ++
          fr"ORDER BY i.organization_id ASC, i.catalogue_item_id ASC," ++
          fr"v.created_at ASC, v.catalogue_item_variant_id ASC"

      q.query[CatalogueItemWithVariantJoinedRow]
        .to[List]
        .map { catalogueItemWithVariantJoinedRows =>
          val catalogueItemOwnerships = catalogueItemWithVariantJoinedRows
            .map(catalogueItemWithVariantJoinedRow =>
              (
                catalogueItemWithVariantJoinedRow.catalogueItemRow.organizationID,
                catalogueItemWithVariantJoinedRow.catalogueItemRow.catalogueItemID,
              )
            )
            .distinct
          val catalogueItemWithVariantJoinedRowsByOwnership =
            catalogueItemWithVariantJoinedRows.groupBy(catalogueItemWithVariantJoinedRow =>
              (
                catalogueItemWithVariantJoinedRow.catalogueItemRow.organizationID,
                catalogueItemWithVariantJoinedRow.catalogueItemRow.catalogueItemID,
              )
            )

          catalogueItemOwnerships.flatMap { case (organizationID, catalogueItemID) =>
            catalogueItemWithVariantJoinedRowsByOwnership
              .get((organizationID, catalogueItemID))
              .flatMap(NonEmptyChunk.fromIterableOption)
              .map(catalogueItemWithVariantJoinedRowsNonEmpty =>
                CatalogueItemWithVariantsRow(
                  catalogueItemWithVariantJoinedRowsNonEmpty.head.catalogueItemRow,
                  catalogueItemWithVariantJoinedRowsNonEmpty.map(
                    _.into[CatalogueItemVariantRow]
                      .withFieldConst(_.organizationID, organizationID)
                      .withFieldConst(_.catalogueItemID, catalogueItemID)
                      .transform
                  ),
                )
              )
          }
        }
    }
}

object CatalogueQueries {

  // The variant's organization_id/catalogue_item_id are not re-selected from the join; they equal the item's.
  private case class CatalogueItemWithVariantJoinedRow(
      catalogueItemRow: CatalogueItemRow,
      catalogueItemVariantID: CatalogueItemVariantID,
      sku: Option[CatalogueItemVariantSku],
      label: Option[CatalogueItemVariantLabel],
      description: Option[CatalogueItemVariantDescription],
      attributes: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
      price: Option[CatalogueItemVariantPrice],
      sellingUnit: CatalogueItemVariantSaleUnit,
      status: CatalogueItemVariantStatus,
      isArchived: Boolean,
      createdAt: CreatedAt,
      updatedAt: UpdatedAt,
  )

  private case class CatalogueItemSummaryWithVariantSummaryJoinedRow(
      catalogueItemID: CatalogueItemID,
      name: CatalogueItemName,
      imageAsset: Option[CatalogueItemImageAsset],
      status: CatalogueItemStatus,
      catalogueItemVariantSummaryRow: CatalogueItemVariantSummaryRow,
  )

  val live = ZLayer.derive[CatalogueQueries]
}
