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

  private given customerEmailEntryInputsMeta: Meta[List[CustomerEmailEntryInput]]             = jsonbMeta
  private given customerPhoneNumberEntryInputsMeta: Meta[List[CustomerPhoneNumberEntryInput]] = jsonbMeta

  private val frSchema = Fragment.const(config.schema)

  private val frCustomerTableName                = Fragment.const(config.customerTable)
  private val frCustomerBusinessContactTableName = Fragment.const(config.customerBusinessContactTable)

  private val frCustomerTable                = frSchema ++ fr0"." ++ frCustomerTableName
  private val frCustomerBusinessContactTable = frSchema ++ fr0"." ++ frCustomerBusinessContactTableName

  // Individual and business customers share the single `customer` table, distinguished by
  // `customer_type`. The two typed rows select a type-specific subset of columns (individuals
  // have no `tax_id`; `name` maps to `full_name` / `business_name` on the row).
  //
  // `status` is a native `customer_status` enum. The generic `CustomerStatus` codec (derived from
  // its string labels) binds/reads it as text, so writes cast the bound param `?::customer_status`
  // and selects read `status::text` — any new query touching `status` must keep those casts.

  private val frCustomerIndividualInsertFields =
    fr"""
        |organization_id,
        |customer_id,
        |customer_type,
        |name,
        |emails,
        |phone_numbers,
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
        |status,
        |created_at,
        |updated_at
         """.stripMargin

  private def frCustomerIndividualValues(row: CustomerIndividualDetailsRow): Fragment =
    fr0"(" ++ List(
      fr0"${row.organizationID}",
      fr0"${row.customerID}",
      fr0"${CustomerType.Individual}",
      fr0"${row.fullName}",
      fr0"${row.emails}",
      fr0"${row.phoneNumbers}",
      fr0"${row.addressLine1}",
      fr0"${row.addressLine2}",
      fr0"${row.city}",
      fr0"${row.postalCode}",
      fr0"${row.country}",
      fr0"${row.status}::customer_status",
      fr0"${row.createdAt}",
      fr0"${row.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  private val frCustomerIndividualSelectFields =
    fr"""
        |organization_id,
        |customer_id,
        |name,
        |emails,
        |phone_numbers,
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
        |status::text,
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
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
        |status,
        |created_at,
        |updated_at
         """.stripMargin

  private def frCustomerBusinessValues(row: CustomerBusinessDetailsRow): Fragment =
    fr0"(" ++ List(
      fr0"${row.organizationID}",
      fr0"${row.customerID}",
      fr0"${CustomerType.Business}",
      fr0"${row.businessName}",
      fr0"${row.taxID}",
      fr0"${row.emails}",
      fr0"${row.phoneNumbers}",
      fr0"${row.addressLine1}",
      fr0"${row.addressLine2}",
      fr0"${row.city}",
      fr0"${row.postalCode}",
      fr0"${row.country}",
      fr0"${row.status}::customer_status",
      fr0"${row.createdAt}",
      fr0"${row.updatedAt}",
    ).intercalate(fr",") ++ fr0")"

  private val frCustomerBusinessSelectFields =
    fr"""
        |organization_id,
        |customer_id,
        |name,
        |tax_id,
        |emails,
        |phone_numbers,
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
        |status::text,
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
        |updated_at
         """.stripMargin

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
      addressLine1OptUpdate: Option[CustomerAddressLine1] = None,
      addressLine2OptUpdate: Option[CustomerAddressLine2] = None,
      cityOptUpdate: Option[CustomerCity] = None,
      postalCodeOptUpdate: Option[CustomerPostalCode] = None,
      countryOptUpdate: Option[CustomerCountry] = None,
  ): TranzactIO[CustomerIndividualDetailsRow] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      fullNameOptUpdate.map(v => fr"name = $v"),
      emailsOptUpdate.map(v => fr"emails = $v"),
      phoneNumbersOptUpdate.map(v => fr"phone_numbers = $v"),
      addressLine1OptUpdate.map(v => fr"address_line_1 = $v"),
      addressLine2OptUpdate.map(v => fr"address_line_2 = $v"),
      cityOptUpdate.map(v => fr"city = $v"),
      postalCodeOptUpdate.map(v => fr"postal_code = $v"),
      countryOptUpdate.map(v => fr"country = $v"),
    ).flatten

    tzio {
      val q =
        fr"UPDATE" ++ frCustomerTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"customer_type = ${CustomerType.Individual}",
          ) ++
          fr"RETURNING" ++ frCustomerIndividualSelectFields

      q.query[CustomerIndividualDetailsRow].unique
    }
  }

  def updateCustomerBusinessDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
      updatedAt: UpdatedAt,
      businessNameOptUpdate: Option[CustomerBusinessName] = None,
      emailsOptUpdate: Option[List[CustomerEmailEntryInput]] = None,
      taxIDOptUpdate: Option[CustomerTaxID] = None,
      phoneNumbersOptUpdate: Option[List[CustomerPhoneNumberEntryInput]] = None,
      addressLine1OptUpdate: Option[CustomerAddressLine1] = None,
      addressLine2OptUpdate: Option[CustomerAddressLine2] = None,
      cityOptUpdate: Option[CustomerCity] = None,
      postalCodeOptUpdate: Option[CustomerPostalCode] = None,
      countryOptUpdate: Option[CustomerCountry] = None,
  ): TranzactIO[CustomerBusinessDetailsRow] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      businessNameOptUpdate.map(v => fr"name = $v"),
      taxIDOptUpdate.map(v => fr"tax_id = $v"),
      emailsOptUpdate.map(v => fr"emails = $v"),
      phoneNumbersOptUpdate.map(v => fr"phone_numbers = $v"),
      addressLine1OptUpdate.map(v => fr"address_line_1 = $v"),
      addressLine2OptUpdate.map(v => fr"address_line_2 = $v"),
      cityOptUpdate.map(v => fr"city = $v"),
      postalCodeOptUpdate.map(v => fr"postal_code = $v"),
      countryOptUpdate.map(v => fr"country = $v"),
    ).flatten

    tzio {
      val q =
        fr"UPDATE" ++ frCustomerTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"customer_type = ${CustomerType.Business}",
          ) ++
          fr"RETURNING" ++ frCustomerBusinessSelectFields

      q.query[CustomerBusinessDetailsRow].unique
    }
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
          )

      q.query[CustomerIndividualDetailsRow].option
    }

  def getCustomerBusinessDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): TranzactIO[Option[CustomerBusinessDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerBusinessSelectFields ++
          fr"FROM" ++ frCustomerTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            fr"customer_type = ${CustomerType.Business}",
          )

      q.query[CustomerBusinessDetailsRow].option
    }

  def getCustomerSummaryRows(organizationID: OrganizationID): TranzactIO[List[CustomerSummaryRow]] =
    tzio {
      val q =
        fr"SELECT customer_id, name, customer_type" ++
          fr"FROM" ++ frCustomerTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"status = ${CustomerStatus.Active}::customer_status",
          ) ++
          fr"ORDER BY LOWER(name), customer_id"

      q.query[CustomerSummaryRow].to[List]
    }

  def deleteCustomerBusinessContactRows(
      organizationID: OrganizationID,
      customerID: CustomerID,
      customerBusinessContactIDs: NonEmptyList[CustomerBusinessContactID],
  ): TranzactIO[Int] =
    tzio {
      val q =
        fr"DELETE FROM" ++ frCustomerBusinessContactTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
            in(fr"customer_business_contact_id", customerBusinessContactIDs),
          )

      q.update.run
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

  val live = ZLayer.derive[CustomerBookQueries]
}
