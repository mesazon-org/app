package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
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
        |logo_original_bucket_key,
        |logo_normalized_bucket_key,
        |logo_original_file_name,
        |created_at,
        |updated_at
         """.stripMargin

  def get(organizationID: OrganizationID): TranzactIO[Option[OrganizationDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frOrganizationDetailsFields ++
          fr"FROM" ++ frTable ++
          whereAnd(fr"organization_id = $organizationID")

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
      updatedAt: UpdatedAt,
      organizationStageOptUpdate: Option[OrganizationStage],
      nameOptUpdate: Option[OrganizationName],
      slugOptUpdate: Option[OrganizationSlug],
      emailOptUpdate: Option[OrganizationEmail],
      phoneNumberOptUpdate: Option[OrganizationPhoneNumber],
      addressLine1OptUpdate: Option[OrganizationAddressLine1],
      addressLine2OptUpdate: Option[OrganizationAddressLine2],
      cityOptUpdate: Option[OrganizationCity],
      postalCodeOptUpdate: Option[OrganizationPostalCode],
      countryOptUpdate: Option[OrganizationCountry],
      logoOriginalBucketKeyOptUpdate: Option[OrganizationLogoOriginalBucketKey] = None,
      logoNormalizedBucketKeyOptUpdate: Option[OrganizationLogoNormalizedBucketKey] = None,
      logoOriginalFileNameOptUpdate: Option[OrganizationLogoOriginalFileName] = None,
  ): TranzactIO[OrganizationDetailsRow] = {
    val updates = NonEmptyList.of(
      fr"updated_at = $updatedAt"
    ) ++ List(
      organizationStageOptUpdate.map(v => fr"organization_stage = $v"),
      nameOptUpdate.map(v => fr"name = $v"),
      slugOptUpdate.map(v => fr"slug = $v"),
      emailOptUpdate.map(v => fr"email = $v"),
      phoneNumberOptUpdate.map(v => fr"phone_region = ${v.value.phoneRegion}"),
      phoneNumberOptUpdate.map(v => fr"phone_country_code = ${v.value.phoneCountryCode}"),
      phoneNumberOptUpdate.map(v => fr"phone_national_number = ${v.value.phoneNationalNumber}"),
      phoneNumberOptUpdate.map(v => fr"phone_number_e164 = ${v.value.phoneNumberE164}"),
      addressLine1OptUpdate.map(v => fr"address_line_1 = $v"),
      addressLine2OptUpdate.map(v => fr"address_line_2 = $v"),
      cityOptUpdate.map(v => fr"city = $v"),
      postalCodeOptUpdate.map(v => fr"postal_code = $v"),
      countryOptUpdate.map(v => fr"country = $v"),
      logoOriginalBucketKeyOptUpdate.map(v => fr"logo_original_bucket_key = $v"),
      logoNormalizedBucketKeyOptUpdate.map(v => fr"logo_normalized_bucket_key = $v"),
      logoOriginalFileNameOptUpdate.map(v => fr"logo_original_file_name = $v"),
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
          whereAnd(fr"slug = $slug")

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
