package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

final class OrganizationUserQueries(
    config: RepositoryConfig
) {

  private val frSchema                   = Fragment.const(config.schema)
  private val frOrganizationDetailsTable = Fragment.const(config.organizationUserTable)

  val organizationUserFields =
    fr"""
        |organization_id,
        |user_id,
        |user_role,
        |created_at,
        |updated_at
          """.stripMargin

  def insert(organizationUserRow: OrganizationUserRow): TranzactIO[OrganizationUserRow] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frOrganizationDetailsTable ($organizationUserFields)
           |VALUES ($organizationUserRow)
           |RETURNING $organizationUserFields
           |""".stripMargin.query[OrganizationUserRow].unique
    }

  // Testing
  def getAllOrganizationUserTesting: TranzactIO[List[OrganizationUserRow]] =
    tzio {
      sql"""
           |SELECT $organizationUserFields
           |FROM $frSchema.$frOrganizationDetailsTable
           |""".stripMargin.query[OrganizationUserRow].to[List]
    }
}

object OrganizationUserQueries {

  val live = ZLayer.derive[OrganizationUserQueries]
}
