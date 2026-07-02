package io.mesazon.gateway.repository.queries

import cats.syntax.all.*
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
  private val frTable                 = frSchema ++ fr0"." ++ frOrganizationUserTable

  val frOrganizationUserFields =
    fr"""
        |organization_id,
        |user_id,
        |user_role,
        |created_at,
        |updated_at
         """.stripMargin

  def insert(organizationUserRow: OrganizationUserRow): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frOrganizationUserFields ++ fr")" ++
          fr"VALUES (" ++ fr"$organizationUserRow" ++ fr")"

      q.update.run.void
    }

  // Testing
  def getAllOrganizationUsersTesting: TranzactIO[List[OrganizationUserRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frOrganizationUserFields ++
          fr"FROM" ++ frTable

      q.query[OrganizationUserRow].to[List]
    }
}

object OrganizationUserQueries {

  val live = ZLayer.derive[OrganizationUserQueries]
}
