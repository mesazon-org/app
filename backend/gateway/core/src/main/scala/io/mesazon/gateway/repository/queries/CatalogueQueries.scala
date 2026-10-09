package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import io.github.gaelrenoux.tranzactio.doobie.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
import zio.*

final class CatalogueQueries(
    config: RepositoryConfig
) {

  private given catalogueItemVariantUnitCodec: JsonValueCodec[CatalogueItemVariantUnit] =
    new JsonValueCodec[CatalogueItemVariantUnit] {
      override def decodeValue(in: JsonReader, default: CatalogueItemVariantUnit): CatalogueItemVariantUnit = {
        val catalogueItemVariantUnitRaw = in.readString(null)
        CatalogueItemVariantUnit.values
          .find(_.toString == catalogueItemVariantUnitRaw)
          .getOrElse(in.decodeError(s"Unknown CatalogueItemVariantUnit: [$catalogueItemVariantUnitRaw]"))
      }

      override def encodeValue(catalogueItemVariantUnit: CatalogueItemVariantUnit, out: JsonWriter): Unit =
        out.writeVal(catalogueItemVariantUnit.toString)

      override def nullValue: CatalogueItemVariantUnit = null
    }

  private given catalogueItemVariantUnitPerUnitCodec: JsonValueCodec[CatalogueItemVariantUnit.PerUnit] =
    JsonCodecMaker.makeWithoutDiscriminator

  private given catalogueItemVariantUnitUnitSizeCodec: JsonValueCodec[CatalogueItemVariantUnit.UnitSize] =
    JsonCodecMaker.makeWithoutDiscriminator

  private given catalogueItemVariantSaleUnitCodec: JsonValueCodec[CatalogueItemVariantSaleUnit] =
    JsonCodecMaker.make

  private given catalogueItemVariantAttributeNameKeyCodec: JsonKeyCodec[CatalogueItemVariantAttributeName] =
    new JsonKeyCodec[CatalogueItemVariantAttributeName] {
      override def decodeKey(in: JsonReader): CatalogueItemVariantAttributeName = {
        val catalogueItemVariantAttributeNameRaw = in.readKeyAsString()
        CatalogueItemVariantAttributeName
          .either(catalogueItemVariantAttributeNameRaw)
          .fold(errorMessage => in.decodeError(errorMessage), identity)
      }

      override def encodeKey(catalogueItemVariantAttributeName: CatalogueItemVariantAttributeName, out: JsonWriter): Unit =
        out.writeKey(catalogueItemVariantAttributeName.value)
    }

  private given catalogueItemVariantAttributesCodec
      : JsonValueCodec[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] =
    JsonCodecMaker.make

  private given catalogueItemVariantAttributesMeta
      : Meta[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] = jsonbMeta

  private given catalogueItemVariantSaleUnitMeta: Meta[CatalogueItemVariantSaleUnit] = jsonbMeta

  private val frSchema                  = Fragment.const(config.schema)
  private val frCatalogueItemTableName  = Fragment.const(config.catalogueItemTable)
  private val frCatalogueItemTable      = frSchema ++ fr0"." ++ frCatalogueItemTableName
  private val frCatalogueItemStatusType = frSchema ++ fr0".catalogue_item_status"

  private val frCatalogueItemVariantTableName  = Fragment.const(config.catalogueItemVariantTable)
  private val frCatalogueItemVariantTable      = frSchema ++ fr0"." ++ frCatalogueItemVariantTableName
  private val frCatalogueItemVariantStatusType = frSchema ++ fr0".catalogue_item_variant_status"

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
        |created_at,
        |updated_at
         """.stripMargin

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
        |created_at,
        |updated_at
         """.stripMargin

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
        |created_at,
        |updated_at
         """.stripMargin

  private def frCatalogueItemValues(row: CatalogueItemRowV2): Fragment =
    fr0"(" ++ List(
      fr0"${row.organizationID}",
      fr0"${row.catalogueItemID}",
      fr0"${row.name}",
      fr0"${row.description}",
      fr0"${row.imageAsset.map(_.value.imageOriginalS3BucketKey)}",
      fr0"${row.imageAsset.map(_.value.imageNormalizedS3BucketKey)}",
      fr0"${row.imageAsset.map(_.value.imageOriginalFileName)}",
      fr0"${row.status}::" ++ frCatalogueItemStatusType,
      fr0"${row.createdAt}",
      fr0"${row.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

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
        |created_at,
        |updated_at
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

  def insertCatalogueItemRow(catalogueItemRowV2: CatalogueItemRowV2): TranzactIO[Unit] =
    insertRowWith(frCatalogueItemTable, frCatalogueItemInsertFields, frCatalogueItemValues, catalogueItemRowV2)

  def insertCatalogueItemRows(catalogueItemRowsV2: List[CatalogueItemRowV2]): TranzactIO[Unit] =
    insertRowsWith(frCatalogueItemTable, frCatalogueItemInsertFields, frCatalogueItemValues, catalogueItemRowsV2)

  def updateCatalogueItemRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
      nameOptUpdate: Option[CatalogueItemName] = None,
      descriptionOptUpdate: Option[Option[CatalogueItemDescription]] = None,
      imageAssetOptUpdate: Option[CatalogueItemImageAsset] = None,
  ): TranzactIO[Option[CatalogueItemRowV2]] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      nameOptUpdate.map(v => fr"name = $v"),
      descriptionOptUpdate.map(v => fr"description = $v"),
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

      q.query[CatalogueItemRowV2].option
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

  def getCatalogueItemRowActive(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[Option[CatalogueItemRowV2]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          )

      q.query[CatalogueItemRowV2].option
    }

  def getCatalogueItemRowsActive(organizationID: OrganizationID): TranzactIO[List[CatalogueItemRowV2]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          )

      q.query[CatalogueItemRowV2].to[List]
    }

  def getCatalogueItemRowActiveForUpdate(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[Option[CatalogueItemRowV2]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr0"status = ${CatalogueItemStatus.Active}::" ++ frCatalogueItemStatusType,
          ) ++
          fr"FOR UPDATE"

      q.query[CatalogueItemRowV2].option
    }

  def insertCatalogueItemVariantRow(catalogueItemVariantRow: CatalogueItemVariantRow): TranzactIO[Unit] =
    insertRowWith(
      frCatalogueItemVariantTable,
      frCatalogueItemVariantInsertFields,
      frCatalogueItemVariantValues,
      catalogueItemVariantRow,
    )

  def insertCatalogueItemVariantRows(catalogueItemVariantRows: List[CatalogueItemVariantRow]): TranzactIO[Unit] =
    insertRowsWith(
      frCatalogueItemVariantTable,
      frCatalogueItemVariantInsertFields,
      frCatalogueItemVariantValues,
      catalogueItemVariantRows,
    )

  def updateCatalogueItemVariantRow(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemVariantID: CatalogueItemVariantID,
      updatedAt: UpdatedAt,
      skuOptUpdate: Option[Option[CatalogueItemVariantSku]] = None,
      labelOptUpdate: Option[Option[CatalogueItemVariantLabel]] = None,
      descriptionOptUpdate: Option[Option[CatalogueItemVariantDescription]] = None,
      attributesOptUpdate: Option[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] = None,
      priceOptUpdate: Option[Option[CatalogueItemVariantPrice]] = None,
      sellingUnitOptUpdate: Option[CatalogueItemVariantSaleUnit] = None,
      statusOptUpdate: Option[CatalogueItemVariantStatus] = None,
  ): TranzactIO[Option[CatalogueItemVariantRow]] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      skuOptUpdate.map(v => fr"sku = $v"),
      labelOptUpdate.map(v => fr"label = $v"),
      descriptionOptUpdate.map(v => fr"description = $v"),
      attributesOptUpdate.map(v => fr"attributes = $v"),
      priceOptUpdate.map(v => fr"price_amount = ${v.map(_.value.amount)}"),
      priceOptUpdate.map(v => fr"price_currency = ${v.map(_.value.currency)}"),
      sellingUnitOptUpdate.map(v => fr"selling_unit = $v"),
      statusOptUpdate.map(v => fr0"status = $v::" ++ frCatalogueItemVariantStatusType),
    ).flatten

    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemVariantTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr"catalogue_item_variant_id = $catalogueItemVariantID",
            fr0"status <> ${CatalogueItemVariantStatus.Archived}::" ++ frCatalogueItemVariantStatusType,
          ) ++
          fr"RETURNING" ++ frCatalogueItemVariantSelectFields

      q.query[CatalogueItemVariantRow].option
    }
  }

  def archiveCatalogueItemVariantRow(
      organizationID: OrganizationID,
      catalogueItemVariantID: CatalogueItemVariantID,
      updatedAt: UpdatedAt,
  ): TranzactIO[Option[CatalogueItemVariantID]] =
    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemVariantTable ++
          set(
            fr0"status = ${CatalogueItemVariantStatus.Archived}::" ++ frCatalogueItemVariantStatusType,
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_variant_id = $catalogueItemVariantID",
            fr0"status <> ${CatalogueItemVariantStatus.Archived}::" ++ frCatalogueItemVariantStatusType,
          ) ++
          fr"RETURNING catalogue_item_variant_id"

      q.query[CatalogueItemVariantID].option
    }

  def archiveCatalogueItemVariantRowsByCatalogueItemID(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      updatedAt: UpdatedAt,
  ): TranzactIO[List[CatalogueItemVariantID]] =
    tzio {
      val q =
        fr"UPDATE" ++ frCatalogueItemVariantTable ++
          set(
            fr0"status = ${CatalogueItemVariantStatus.Archived}::" ++ frCatalogueItemVariantStatusType,
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr0"status <> ${CatalogueItemVariantStatus.Archived}::" ++ frCatalogueItemVariantStatusType,
          ) ++
          fr"RETURNING catalogue_item_variant_id"

      q.query[CatalogueItemVariantID].to[List]
    }

  def getCatalogueItemVariantRow(
      organizationID: OrganizationID,
      catalogueItemVariantID: CatalogueItemVariantID,
  ): TranzactIO[Option[CatalogueItemVariantRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemVariantSelectFields ++
          fr"FROM" ++ frCatalogueItemVariantTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_variant_id = $catalogueItemVariantID",
          )

      q.query[CatalogueItemVariantRow].option
    }

  def getCatalogueItemVariantRows(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[List[CatalogueItemVariantRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemVariantSelectFields ++
          fr"FROM" ++ frCatalogueItemVariantTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
          ) ++
          orderBy(fr"catalogue_item_variant_id")

      q.query[CatalogueItemVariantRow].to[List]
    }

  def getCatalogueItemVariantRowsByCatalogueItemIDs(
      organizationID: OrganizationID,
      catalogueItemIDs: NonEmptyList[CatalogueItemID],
  ): TranzactIO[List[CatalogueItemVariantRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemVariantSelectFields ++
          fr"FROM" ++ frCatalogueItemVariantTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            in(fr"catalogue_item_id", catalogueItemIDs),
          ) ++
          orderBy(fr"catalogue_item_id", fr"catalogue_item_variant_id")

      q.query[CatalogueItemVariantRow].to[List]
    }

  def countCatalogueItemVariantRowsNonArchived(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
  ): TranzactIO[Long] =
    tzio {
      val q =
        fr"SELECT count(*)" ++
          fr"FROM" ++ frCatalogueItemVariantTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"catalogue_item_id = $catalogueItemID",
            fr0"status <> ${CatalogueItemVariantStatus.Archived}::" ++ frCatalogueItemVariantStatusType,
          )

      q.query[Long].unique
    }

  // Testing
  def getAllCatalogueItemRowsTesting: TranzactIO[List[CatalogueItemRowV2]] =
    tzio {
      val q = fr"SELECT" ++ frCatalogueItemSelectFields ++ fr"FROM" ++ frCatalogueItemTable
      q.query[CatalogueItemRowV2].to[List]
    }

  def getAllCatalogueItemRowsArchivedTesting: TranzactIO[List[CatalogueItemRowV2]] =
    tzio {
      val q =
        fr"SELECT" ++ frCatalogueItemSelectFields ++
          fr"FROM" ++ frCatalogueItemTable ++
          whereAnd(fr0"status = ${CatalogueItemStatus.Archived}::" ++ frCatalogueItemStatusType)

      q.query[CatalogueItemRowV2].to[List]
    }

  def getAllCatalogueItemVariantRowsTesting: TranzactIO[List[CatalogueItemVariantRow]] =
    tzio {
      val q = fr"SELECT" ++ frCatalogueItemVariantSelectFields ++ fr"FROM" ++ frCatalogueItemVariantTable
      q.query[CatalogueItemVariantRow].to[List]
    }
}

object CatalogueQueries {

  val live = ZLayer.derive[CatalogueQueries]
}
