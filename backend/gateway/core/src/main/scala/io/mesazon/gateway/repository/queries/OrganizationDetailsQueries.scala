package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.fragments.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

final class OrganizationDetailsQueries(
    config: RepositoryConfig
) {

  private val frSchema                   = Fragment.const(config.schema)
  private val frOrganizationDetailsTable = Fragment.const(config.organizationDetailsTable)
  private val frTable                    = frSchema ++ fr0"." ++ frOrganizationDetailsTable

  val frOrganizationDetailsFields =
    fr"""
        |organization_id,
        |name,
        |slug,
        |email,
        |phone_region,
        |phone_country_code,
        |phone_national_number,
        |phone_number_e164,
        |organization_stage,
        |address_line_1,
        |address_line_2,
        |city,
        |postal_code,
        |country,
        |logo_file_name,
        |logo_bucket_key,
        |created_at,
        |updated_at
         """.stripMargin

  def get(organizationID: OrganizationID): TranzactIO[Option[OrganizationDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frOrganizationDetailsFields ++
          fr"FROM" ++ frTable ++
          fr"WHERE organization_id = $organizationID"

      q.query[OrganizationDetailsRow].option
    }

  def insert(organizationDetailsRow: OrganizationDetailsRow): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frOrganizationDetailsFields ++ fr")" ++
          fr"VALUES (" ++ fr"$organizationDetailsRow" ++ fr")"
      q.update.run.void
    }

  def update(
      organizationID: OrganizationID,
      organizationStage: OrganizationStage,
      updatedAt: UpdatedAt,
      nameUpdate: Option[OrganizationName],
      slugUpdate: Option[OrganizationSlug],
      emailUpdate: Option[OrganizationEmail],
      phoneNumberUpdate: Option[OrganizationPhoneNumber],
      addressLine1Update: Option[OrganizationAddressLine1],
      addressLine2Update: Option[OrganizationAddressLine2],
      cityUpdate: Option[OrganizationCity],
      postalCodeUpdate: Option[OrganizationPostalCode],
      countryUpdate: Option[OrganizationCountry],
      logoBucketKeyUpdate: Option[OrganizationLogoBucketKey],
      logoFileNameUpdate: Option[OrganizationLogoFileName],
  ): TranzactIO[OrganizationDetailsRow] = {
    val updates = NonEmptyList.of(
      fr"organization_stage = $organizationStage",
      fr"updated_at = $updatedAt",
    ) ++ List(
      nameUpdate.map(v => fr"name = $v"),
      slugUpdate.map(v => fr"slug = $v"),
      emailUpdate.map(v => fr"email = $v"),
      phoneNumberUpdate.map(v => fr"phone_region = ${v.value.phoneRegion}"),
      phoneNumberUpdate.map(v => fr"phone_country_code = ${v.value.phoneCountryCode}"),
      phoneNumberUpdate.map(v => fr"phone_national_number = ${v.value.phoneNationalNumber}"),
      phoneNumberUpdate.map(v => fr"phone_number_e164 = ${v.value.phoneNumberE164}"),
      addressLine1Update.map(v => fr"address_line_1 = $v"),
      addressLine2Update.map(v => fr"address_line_2 = $v"),
      cityUpdate.map(v => fr"city = $v"),
      postalCodeUpdate.map(v => fr"postal_code = $v"),
      countryUpdate.map(v => fr"country = $v"),
      logoBucketKeyUpdate.map(v => fr"logo_bucket_key = $v"),
      logoFileNameUpdate.map(v => fr"logo_file_name = $v"),
    ).flatten

    tzio {
      val query = fr"UPDATE" ++ frTable ++
        set(updates) ++
        whereAnd(fr"organization_id = $organizationID") ++
        fr"RETURNING" ++ frOrganizationDetailsFields

      query.query[OrganizationDetailsRow].unique
    }
  }

  def slugExist(slug: OrganizationSlug): TranzactIO[Boolean] =
    tzio {
      val q =
        fr"SELECT 1 FROM" ++ frTable ++
          fr"WHERE slug = $slug"

      q.query[Int].option.map(_.isDefined)
    }

  // Testing
  def getAllOrganizationDetailsTesting: TranzactIO[List[OrganizationDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frOrganizationDetailsFields ++
          fr"FROM" ++ frTable

      q.query[OrganizationDetailsRow].to[List]
    }
}

object OrganizationDetailsQueries {

  val live = ZLayer.derive[OrganizationDetailsQueries]
}
