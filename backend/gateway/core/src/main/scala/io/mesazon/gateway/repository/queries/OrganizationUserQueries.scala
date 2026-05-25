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

  private val frSchema                = Fragment.const(config.schema)
  private val frOrganizationUserTable = Fragment.const(config.organizationUserTable)

  val organizationUserFields =
    fr"""
        |organization_id,
        |user_id,
        |user_role,
        |created_at,
        |updated_at
          """.stripMargin

  def insert(organizationUserRow: OrganizationUserRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frOrganizationUserTable ($organizationUserFields)
           |VALUES ($organizationUserRow)
           |""".stripMargin.update.run.map(_ => ())
    }

  // Testing
  def getAllOrganizationUsersTesting: TranzactIO[List[OrganizationUserRow]] =
    tzio {
      sql"""
           |SELECT $organizationUserFields
           |FROM $frSchema.$frOrganizationUserTable
           |""".stripMargin.query[OrganizationUserRow].to[List]
    }
}

object OrganizationUserQueries {

  val live = ZLayer.derive[OrganizationUserQueries]
}
