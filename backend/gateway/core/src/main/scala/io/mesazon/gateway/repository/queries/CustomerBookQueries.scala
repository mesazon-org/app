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

  private val frCustomerTableName                  = Fragment.const(config.customerTable)
  private val frCustomerIndividualDetailsTableName = Fragment.const(config.customerIndividualDetailsTable)
  private val frCustomerBusinessDetailsTableName   = Fragment.const(config.customerBusinessDetailsTable)
  private val frCustomerBusinessContactTableName   = Fragment.const(config.customerBusinessContactTable)

  private val frCustomerTable                  = frSchema ++ fr0"." ++ frCustomerTableName
  private val frCustomerIndividualDetailsTable = frSchema ++ fr0"." ++ frCustomerIndividualDetailsTableName
  private val frCustomerBusinessDetailsTable   = frSchema ++ fr0"." ++ frCustomerBusinessDetailsTableName
  private val frCustomerBusinessContactTable   = frSchema ++ fr0"." ++ frCustomerBusinessContactTableName

  private val frCustomerFields =
    fr"""
        |organization_id,
        |customer_id,
        |customer_type,
        |status,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCustomerIndividualDetailsFields =
    fr"""
        |organization_id,
        |customer_id,
        |full_name,
        |emails,
        |phone_numbers,
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
        |created_at,
        |updated_at
         """.stripMargin

  private val frCustomerBusinessDetailsFields =
    fr"""
        |organization_id,
        |customer_id,
        |business_name,
        |emails,
        |phone_numbers,
        |tax_id,
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
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

  private def insertRow[Row: Write](frTable: Fragment, frFields: Fragment, row: Row): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frFields ++ fr")" ++
          fr"VALUES (" ++ fr"$row" ++ fr")"

      q.update.run.void
    }

  private def insertRows[Row: Write](frTable: Fragment, frFields: Fragment, rows: List[Row]): TranzactIO[Unit] =
    NonEmptyList.fromList(rows).fold(ZIO.unit: TranzactIO[Unit]) { rowsNel =>
      tzio {
        val frValues = rowsNel.toList.map(row => fr0"(" ++ fr"$row" ++ fr0")").intercalate(fr",")
        val q        =
          fr"INSERT INTO" ++ frTable ++
            fr"(" ++ frFields ++ fr")" ++
            fr"VALUES" ++ frValues

        q.update.run.void
      }
    }

  def insertCustomerRow(customerRow: CustomerRow): TranzactIO[Unit] =
    insertRow(frCustomerTable, frCustomerFields, customerRow)

  def insertCustomerRows(customerRows: List[CustomerRow]): TranzactIO[Unit] =
    insertRows(frCustomerTable, frCustomerFields, customerRows)

  def insertCustomerIndividualDetailsRow(
      customerIndividualDetailsRow: CustomerIndividualDetailsRow
  ): TranzactIO[Unit] =
    insertRow(frCustomerIndividualDetailsTable, frCustomerIndividualDetailsFields, customerIndividualDetailsRow)

  def insertCustomerIndividualDetailsRows(
      customerIndividualDetailsRows: List[CustomerIndividualDetailsRow]
  ): TranzactIO[Unit] =
    insertRows(frCustomerIndividualDetailsTable, frCustomerIndividualDetailsFields, customerIndividualDetailsRows)

  def insertCustomerBusinessDetailsRow(
      customerBusinessDetailsRow: CustomerBusinessDetailsRow
  ): TranzactIO[Unit] =
    insertRow(frCustomerBusinessDetailsTable, frCustomerBusinessDetailsFields, customerBusinessDetailsRow)

  def insertCustomerBusinessDetailsRows(
      customerBusinessDetailsRows: List[CustomerBusinessDetailsRow]
  ): TranzactIO[Unit] =
    insertRows(frCustomerBusinessDetailsTable, frCustomerBusinessDetailsFields, customerBusinessDetailsRows)

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
      fullNameOptUpdate.map(v => fr"full_name = $v"),
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
        fr"UPDATE" ++ frCustomerIndividualDetailsTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
          ) ++
          fr"RETURNING" ++ frCustomerIndividualDetailsFields

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
      businessNameOptUpdate.map(v => fr"business_name = $v"),
      emailsOptUpdate.map(v => fr"emails = $v"),
      taxIDOptUpdate.map(v => fr"tax_id = $v"),
      phoneNumbersOptUpdate.map(v => fr"phone_numbers = $v"),
      addressLine1OptUpdate.map(v => fr"address_line_1 = $v"),
      addressLine2OptUpdate.map(v => fr"address_line_2 = $v"),
      cityOptUpdate.map(v => fr"city = $v"),
      postalCodeOptUpdate.map(v => fr"postal_code = $v"),
      countryOptUpdate.map(v => fr"country = $v"),
    ).flatten

    tzio {
      val q =
        fr"UPDATE" ++ frCustomerBusinessDetailsTable ++
          set(updates) ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
          ) ++
          fr"RETURNING" ++ frCustomerBusinessDetailsFields

      q.query[CustomerBusinessDetailsRow].unique
    }
  }

  def getCustomerIndividualDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): TranzactIO[Option[CustomerIndividualDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerIndividualDetailsFields ++
          fr"FROM" ++ frCustomerIndividualDetailsTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
          )

      q.query[CustomerIndividualDetailsRow].option
    }

  def getCustomerBusinessDetailsRow(
      organizationID: OrganizationID,
      customerID: CustomerID,
  ): TranzactIO[Option[CustomerBusinessDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frCustomerBusinessDetailsFields ++
          fr"FROM" ++ frCustomerBusinessDetailsTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"customer_id = $customerID",
          )

      q.query[CustomerBusinessDetailsRow].option
    }

  private val frCustomerSummaryFromJoins =
    Fragment.const(
      s"""FROM ${config.schema}.${config.customerTable} c
         |LEFT JOIN ${config.schema}.${config.customerIndividualDetailsTable} cid
         |  ON cid.organization_id = c.organization_id AND cid.customer_id = c.customer_id
         |LEFT JOIN ${config.schema}.${config.customerBusinessDetailsTable} cbd
         |  ON cbd.organization_id = c.organization_id AND cbd.customer_id = c.customer_id""".stripMargin
    )

  def getCustomerSummaryRows(organizationID: OrganizationID): TranzactIO[List[CustomerSummaryRow]] =
    tzio {
      val q =
        fr"SELECT c.customer_id, COALESCE(cid.full_name, cbd.business_name), c.customer_type" ++
          frCustomerSummaryFromJoins ++
          whereAnd(
            fr"c.organization_id = $organizationID",
            fr"c.status = ${CustomerStatus.Active}",
          )

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
  def getAllCustomerRowsTesting: TranzactIO[List[CustomerRow]] =
    tzio {
      val q = fr"SELECT" ++ frCustomerFields ++ fr"FROM" ++ frCustomerTable
      q.query[CustomerRow].to[List]
    }

  def getAllCustomerIndividualDetailsRowsTesting: TranzactIO[List[CustomerIndividualDetailsRow]] =
    tzio {
      val q = fr"SELECT" ++ frCustomerIndividualDetailsFields ++ fr"FROM" ++ frCustomerIndividualDetailsTable
      q.query[CustomerIndividualDetailsRow].to[List]
    }

  def getAllCustomerBusinessDetailsRowsTesting: TranzactIO[List[CustomerBusinessDetailsRow]] =
    tzio {
      val q = fr"SELECT" ++ frCustomerBusinessDetailsFields ++ fr"FROM" ++ frCustomerBusinessDetailsTable
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
