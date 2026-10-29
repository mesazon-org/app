package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import io.github.gaelrenoux.tranzactio.doobie.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CustomerBookQueries.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
import zio.*

final class CustomerBookQueries(
    config: RepositoryConfig
) {

  private given customerEmailEntryInputsCodec: JsonValueCodec[List[CustomerEmailEntryInput]] = JsonCodecMaker.make
  private given customerPhoneNumberEntryInputsCodec: JsonValueCodec[List[CustomerPhoneNumberEntryInput]] =
    JsonCodecMaker.make
  private given customerAddressEntriesCodec: JsonValueCodec[List[CustomerAddressEntry]] = JsonCodecMaker.make

  private given customerEmailEntryInputsMeta: Meta[List[CustomerEmailEntryInput]]             = jsonbMeta
  private given customerPhoneNumberEntryInputsMeta: Meta[List[CustomerPhoneNumberEntryInput]] = jsonbMeta
  private given customerAddressEntriesMeta: Meta[List[CustomerAddressEntry]]                  = jsonbMeta

  private val frSchema = Fragment.const(config.schema)

  private val frCustomerTableName                = Fragment.const(config.customerTable)
  private val frCustomerBusinessContactTableName = Fragment.const(config.customerBusinessContactTable)

  private val frCustomerTable                = frSchema ++ fr0"." ++ frCustomerTableName
  private val frCustomerBusinessContactTable = frSchema ++ fr0"." ++ frCustomerBusinessContactTableName

  // Individual and business customers share the single `customer` table, distinguished by
  // `customer_type`. The two typed rows select a type-specific subset of columns (individuals
  // have no `tax_id`; `name` maps to `full_name` / `business_name` on the row).

  private val frCustomerIndividualInsertFields =
    fr"""
        |organization_id,
        |customer_id,
        |customer_type,
        |name,
        |emails,
        |phone_numbers,
        |addresses,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCustomerIndividualSelectFields =
    fr"""
        |organization_id,
        |customer_id,
        |name,
        |emails,
        |phone_numbers,
        |addresses,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCustomerBusinessInsertFields =
    fr"""
        |organization_id,
        |customer_id,
        |customer_type,
        |name,
        |tax_id,
        |emails,
        |phone_numbers,
        |addresses,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCustomerBusinessSelectFields =
    fr"""
        |organization_id,
        |customer_id,
        |name,
        |tax_id,
        |emails,
        |phone_numbers,
        |addresses,
        |is_archived,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCustomerBusinessContactFields =
    fr"""
        |organization_id,
        |customer_id,
        |customer_business_contact_id,
        |full_name,
        |role,
        |email,
        |phone_region,
        |phone_country_code,
        |phone_national_number,
        |phone_number_e164,
        |created_at,
        |updated_at,
        |is_archived
         """.stripMargin

  private val frCustomerBusinessWithContactJoinedSelectFields =
    fr"""
        |c.organization_id,
        |c.customer_id,
        |c.name,
        |c.tax_id,
        |c.emails,
        |c.phone_numbers,
        |c.addresses,
        |c.is_archived,
        |c.created_at,
        |c.updated_at,
        |b.organization_id,
        |b.customer_id,
        |b.customer_business_contact_id,
        |b.full_name,
        |b.role,
        |b.email,
        |b.phone_region,
        |b.phone_country_code,
        |b.phone_national_number,
        |b.phone_number_e164,
        |b.created_at,
        |b.updated_at,
        |b.is_archived
         """.stripMargin

  private val frCustomerBusinessContactUpdateValueColumns =
    fr"""
        |customer_business_contact_id,
        |full_name,
        |role,
        |email,
        |phone_region,
        |phone_country_code,
        |phone_national_number,
        |phone_number_e164,
        |updated_at
         """.stripMargin

  private def frCustomerIndividualValues(customerIndividualDetailsRow: CustomerIndividualDetailsRow): Fragment =
    fr0"(" ++ List(
      fr0"${customerIndividualDetailsRow.organizationID}",
      fr0"${customerIndividualDetailsRow.customerID}",
      fr0"${CustomerType.Individual}",
      fr0"${customerIndividualDetailsRow.fullName}",
      fr0"${customerIndividualDetailsRow.emails}",
      fr0"${customerIndividualDetailsRow.phoneNumbers}",
      fr0"${customerIndividualDetailsRow.addresses}",
      fr0"${customerIndividualDetailsRow.isArchived}",
      fr0"${customerIndividualDetailsRow.createdAt}",
      fr0"${customerIndividualDetailsRow.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  private def frCustomerBusinessValues(customerBusinessDetailsRow: CustomerBusinessDetailsRow): Fragment =
    fr0"(" ++ List(
      fr0"${customerBusinessDetailsRow.organizationID}",
      fr0"${customerBusinessDetailsRow.customerID}",
      fr0"${CustomerType.Business}",
      fr0"${customerBusinessDetailsRow.businessName}",
      fr0"${customerBusinessDetailsRow.taxID}",
      fr0"${customerBusinessDetailsRow.emails}",
      fr0"${customerBusinessDetailsRow.phoneNumbers}",
      fr0"${customerBusinessDetailsRow.addresses}",
      fr0"${customerBusinessDetailsRow.isArchived}",
      fr0"${customerBusinessDetailsRow.createdAt}",
      fr0"${customerBusinessDetailsRow.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  // Whole-row insert via doobie's positional `Write` binding — for rows that map 1:1 to their table
  // (e.g. `CustomerBusinessContactRow`). Rows that are a typed view over a shared table build their
  // VALUES explicitly through the `…With` helpers below.
  private def insertRows[Row: Write](frTable: Fragment, frFields: Fragment, rows: List[Row]): TranzactIO[Unit] =
    insertRowsWith(frTable, frFields, (row: Row) => fr0"(" ++ fr"$row" ++ fr0")", rows)

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
    NonEmptyList.fromList(rows).fold(ZIO.unit: TranzactIO[Unit]) { rowsNonEmpty =>
      tzio {
        val frAllValues = rowsNonEmpty.toList.map(frValues).intercalate(fr",")
        val q           =
          fr"INSERT INTO" ++ frTable ++
            fr"(" ++ frFields ++ fr")" ++
            fr"VALUES" ++ frAllValues

        q.update.run.void
      }
    }

  private def frCustomerBusinessContactUpdateValues(customerBusinessContactRow: CustomerBusinessContactRow): Fragment =
    fr0"(" ++ List(
      fr0"${customerBusinessContactRow.customerBusinessContactID}::uuid",
      fr0"${customerBusinessContactRow.fullName}::text",
      fr0"${customerBusinessContactRow.role}::text",
      fr0"${customerBusinessContactRow.email}::text",
      fr0"${customerBusinessContactRow.phoneNumber.map(_.value.phoneRegion)}::text",
      fr0"${customerBusinessContactRow.phoneNumber.map(_.value.phoneCountryCode)}::text",
      fr0"${customerBusinessContactRow.phoneNumber.map(_.value.phoneNationalNumber)}::text",
      fr0"${customerBusinessContactRow.phoneNumber.map(_.value.phoneNumberE164)}::text",
      fr0"${customerBusinessContactRow.updatedAt}::timestamptz",
    ).intercalate(fr",") ++ fr0")"

  def insertCustomerIndividualDetailsRow(
      customerIndividualDetailsRow: CustomerIndividualDetailsRow
  ): TranzactIO[Unit] =
    insertRowWith(
      frCustomerTable,
      frCustomerIndividualInsertFields,
      frCustomerIndividualValues,
      customerIndividualDetailsRow,
    )

  def insertCustomerIndividualDetailsRows(
      customerIndividualDetailsRows: List[CustomerIndividualDetailsRow]
  ): TranzactIO[Unit] =
    insertRowsWith(
      frCustomerTable,
      frCustomerIndividualInsertFields,
      frCustomerIndividualValues,
      customerIndividualDetailsRows,
    )

  def insertCustomerBusinessDetailsRow(
      customerBusinessDetailsRow: CustomerBusinessDetailsRow
  ): TranzactIO[Unit] =
    insertRowWith(frCustomerTable, frCustomerBusinessInsertFields, frCustomerBusinessValues, customerBusinessDetailsRow)

  def insertCustomerBusinessDetailsRows(
      customerBusinessDetailsRows: List[CustomerBusinessDetailsRow]
  ): TranzactIO[Unit] =
    insertRowsWith(
      frCustomerTable,
      frCustomerBusinessInsertFields,
      frCustomerBusinessValues,
      customerBusinessDetailsRows,
    )

  def insertCustomerBusinessContactRows(
      customerBusinessContactRows: List[CustomerBusinessContactRow]
  ): TranzactIO[Unit] =
    insertRows(frCustomerBusinessContactTable, frCustomerBusinessContactFields, customerBusinessContactRows)

  def updateCustomerIndividualDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
      updatedAt: UpdatedAt,
      fullNameOptUpdate: Option[CustomerFullName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressesOptUpdate: Option[List[CustomerAddressEntry]] = None,
  ): TranzactIO[Option[CustomerIndividualDetailsRow]] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      fullNameOptUpdate.map(v => fr"name = $v"),
      emailsOptUpdate.map(v => fr"emails = $v"),
      phoneNumbersOptUpdate.map(v => fr"phone_numbers = $v"),
      addressesOptUpdate.map(v => fr"addresses = $v"),
    ).flatten

    tzio {
      // Only active customers are editable: archived (or absent) rows match nothing, so the update is
      // a silent no-op (`.option` → None).
      val q =
        fr"UPDATE" ++ frCustomerTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"customer_type = ${CustomerType.Individual}",
            fr"is_archived = false",
          ) ++
          fr"RETURNING" ++ frCustomerIndividualSelectFields

      q.query[CustomerIndividualDetailsRow].option
    }
  }

  def updateCustomerBusinessDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
      updatedAt: UpdatedAt,
      businessNameOptUpdate: Option[CustomerBusinessName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      taxIDOptUpdate: Option[Option[CustomerTaxID]] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressesOptUpdate: Option[List[CustomerAddressEntry]] = None,
  ): TranzactIO[Option[CustomerBusinessDetailsRow]] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      businessNameOptUpdate.map(v => fr"name = $v"),
      taxIDOptUpdate.map(v => fr"tax_id = $v"),
      emailsOptUpdate.map(v => fr"emails = $v"),
      phoneNumbersOptUpdate.map(v => fr"phone_numbers = $v"),
      addressesOptUpdate.map(v => fr"addresses = $v"),
    ).flatten

    tzio {
      // Only active customers are editable: archived (or absent) rows match nothing, so the update is
      // a silent no-op (`.option` → None).
      val q =
        fr"UPDATE" ++ frCustomerTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"customer_type = ${CustomerType.Business}",
            fr"is_archived = false",
          ) ++
          fr"RETURNING" ++ frCustomerBusinessSelectFields

      q.query[CustomerBusinessDetailsRow].option
    }
  }

  // Archive (soft-delete) a customer: type-agnostic (matches by id regardless of `customer_type`),
  // but only an *active* one — a missing or already-archived customer matches nothing and returns
  // `None` (a silent no-op for the caller). Removing a row from the active set can never violate the
  // partial `uq_customer_name` index.
  def archiveCustomerRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
      updatedAt: UpdatedAt,
  ): TranzactIO[Option[CustomerID]] =
    tzio {
      val q =
        fr"UPDATE" ++ frCustomerTable ++
          set(
            fr"is_archived = true",
            fr"updated_at = $updatedAt",
          ) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"is_archived = false",
          ) ++
          fr"RETURNING customer_id"

      q.query[CustomerID].option
    }

  def getCustomerIndividualDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): TranzactIO[Option[CustomerIndividualDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerIndividualSelectFields ++
          fr"FROM" ++ frCustomerTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"customer_type = ${CustomerType.Individual}",
            fr"is_archived = false",
          )

      q.query[CustomerIndividualDetailsRow].option
    }

  def getCustomerBusinessWithContactsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): TranzactIO[Option[CustomerBusinessWithContactsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerBusinessWithContactJoinedSelectFields ++
          fr"FROM" ++ frCustomerTable ++ fr"AS c" ++
          fr"LEFT JOIN" ++ frCustomerBusinessContactTable ++ fr"AS b" ++
          fr"ON b.organization_id = c.organization_id" ++
          fr"AND b.customer_id = c.customer_id" ++
          fr"AND b.is_archived = false" ++
          whereAnd(
            fr"c.organization_id = $organizationID",
            fr"c.customer_id = $customerID",
            fr"c.customer_type = ${CustomerType.Business}",
            fr"c.is_archived = false",
          ) ++ orderBy(fr"b.created_at ASC", fr"b.customer_business_contact_id ASC")

      q.query[CustomerBusinessWithContactJoinedRow]
        .to[List]
        .map(customerBusinessWithContactJoinedRows =>
          customerBusinessWithContactJoinedRows.headOption.map(customerBusinessWithContactJoinedRow =>
            CustomerBusinessWithContactsRow(
              customerBusinessWithContactJoinedRow.customerBusinessDetailsRow,
              customerBusinessWithContactJoinedRows.flatMap(_.customerBusinessContactRow),
            )
          )
        )
    }

  def getCustomerSummaryRows(
      organizationID: OrganizationID,
      customerIDs: List[CustomerID],
  ): TranzactIO[List[CustomerSummaryRow]] =
    tzio {
      val q =
        fr"SELECT customer_id, name, customer_type" ++
          fr"FROM" ++ frCustomerTable ++
          whereAndOpt(
            Some(fr"organization_id = $organizationID"),
            Some(fr"is_archived = false"),
            NonEmptyList.fromList(customerIDs).map(customerIDsNonEmpty => in(fr"customer_id", customerIDsNonEmpty)),
          ) ++
          fr"ORDER BY LOWER(name), customer_id"

      q.query[CustomerSummaryRow].to[List]
    }

  def isUniqueCustomerName(
      organizationID: OrganizationID,
      customerName: CustomerName,
      customerType: CustomerType,
      customerIDOpt: Option[CustomerID],
  ): TranzactIO[Boolean] =
    tzio {
      val q =
        fr"SELECT 1 FROM" ++ frCustomerTable ++
          whereAndOpt(
            Some(fr"organization_id = $organizationID"),
            Some(fr"name = $customerName"),
            Some(fr"customer_type = $customerType"),
            Some(fr"is_archived = false"),
            customerIDOpt.map(customerID => fr"customer_id <> $customerID"),
          )

      q.query[Int].option.map(_.isEmpty)
    }

  def getCustomerBusinessContactRowsByIDs(
      organizationID: OrganizationID,
      customerID: CustomerID,
      customerBusinessContactIDs: NonEmptyList[CustomerBusinessContactID],
  ): TranzactIO[List[CustomerBusinessContactRow]] =
    tzio {
      val q = fr"SELECT" ++ frCustomerBusinessContactFields ++ fr"FROM" ++ frCustomerBusinessContactTable ++
        whereAnd(
          fr"organization_id = $organizationID",
          fr"customer_id = $customerID",
          in(fr"customer_business_contact_id", customerBusinessContactIDs),
          fr"is_archived = false",
        ) ++ fr"FOR UPDATE"

      q.query[CustomerBusinessContactRow].to[List]
    }

  def updateCustomerBusinessContactRows(
      organizationID: OrganizationID,
      customerID: CustomerID,
      customerBusinessContactRows: NonEmptyList[CustomerBusinessContactRow],
  ): TranzactIO[Unit] =
    tzio {
      val frAllValues = customerBusinessContactRows.map(frCustomerBusinessContactUpdateValues).intercalate(fr",")
      val q           = fr"UPDATE" ++ frCustomerBusinessContactTable ++ fr"AS t" ++
        fr"""SET full_name = v.full_name, role = v.role, email = v.email,
                  phone_region = v.phone_region, phone_country_code = v.phone_country_code,
                  phone_national_number = v.phone_national_number, phone_number_e164 = v.phone_number_e164,
                  updated_at = v.updated_at""" ++
        fr"FROM (VALUES" ++ frAllValues ++ fr") AS v (" ++ frCustomerBusinessContactUpdateValueColumns ++ fr")" ++
        whereAnd(
          fr"t.customer_business_contact_id = v.customer_business_contact_id",
          fr"t.organization_id = $organizationID",
          fr"t.customer_id = $customerID",
          fr"t.is_archived = false",
        )

      q.update.run.void
    }

  def archiveCustomerBusinessContactRows(
      organizationID: OrganizationID,
      customerID: CustomerID,
      customerBusinessContactIDs: NonEmptyList[CustomerBusinessContactID],
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
    tzio {
      val q = fr"UPDATE" ++ frCustomerBusinessContactTable ++
        set(fr"is_archived = true", fr"updated_at = $updatedAt") ++
        whereAnd(
          fr"organization_id = $organizationID",
          fr"customer_id = $customerID",
          in(fr"customer_business_contact_id", customerBusinessContactIDs),
          fr"is_archived = false",
        )

      q.update.run.void
    }

  // Testing
  def getAllCustomerIDsTesting: TranzactIO[List[CustomerID]] =
    tzio {
      val q = fr"SELECT customer_id FROM" ++ frCustomerTable
      q.query[CustomerID].to[List]
    }

  def getAllCustomerIndividualDetailsRowsTesting: TranzactIO[List[CustomerIndividualDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerIndividualSelectFields ++
          fr"FROM" ++ frCustomerTable ++
          fr"WHERE customer_type = ${CustomerType.Individual}"
      q.query[CustomerIndividualDetailsRow].to[List]
    }

  def getAllCustomerBusinessDetailsRowsTesting: TranzactIO[List[CustomerBusinessDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerBusinessSelectFields ++
          fr"FROM" ++ frCustomerTable ++
          fr"WHERE customer_type = ${CustomerType.Business}"
      q.query[CustomerBusinessDetailsRow].to[List]
    }

  def getAllCustomerBusinessContactRowsTesting: TranzactIO[List[CustomerBusinessContactRow]] =
    tzio {
      val q = fr"SELECT" ++ frCustomerBusinessContactFields ++ fr"FROM" ++ frCustomerBusinessContactTable
      q.query[CustomerBusinessContactRow].to[List]
    }
}

object CustomerBookQueries {

  private case class CustomerBusinessWithContactJoinedRow(
      customerBusinessDetailsRow: CustomerBusinessDetailsRow,
      customerBusinessContactRow: Option[CustomerBusinessContactRow],
  )

  val live = ZLayer.derive[CustomerBookQueries]
}
