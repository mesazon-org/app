package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
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

  val organizationDetailsFields =
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
            |created_at,
            |updated_at
         """.stripMargin

  def insert(organizationDetailsRow: OrganizationDetailsRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frOrganizationDetailsTable ($organizationDetailsFields)
           |VALUES ($organizationDetailsRow)
           |""".stripMargin.update.run.map(_ => ())
    }

  // Testing
  def getAllOrganizationDetailsTesting: TranzactIO[List[OrganizationDetailsRow]] =
    tzio {
      sql"""
             |SELECT $organizationDetailsFields
             |FROM $frSchema.$frOrganizationDetailsTable
             |""".stripMargin.query[OrganizationDetailsRow].to[List]
    }
}

object OrganizationDetailsQueries {

  val live = ZLayer.derive[OrganizationDetailsQueries]
}
