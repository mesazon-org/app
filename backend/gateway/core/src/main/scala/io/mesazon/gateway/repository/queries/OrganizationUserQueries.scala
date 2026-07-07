package io.mesazon.gateway.repository.queries

import cats.syntax.all.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
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

  def get(organizationID: OrganizationID, userID: UserID): TranzactIO[Option[OrganizationUserRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frOrganizationUserFields ++
          fr"FROM" ++ frTable ++
          whereAnd(
            fr"organization_id = $organizationID",
            fr"user_id = $userID",
          )

      q.query[OrganizationUserRow].option
    }

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
