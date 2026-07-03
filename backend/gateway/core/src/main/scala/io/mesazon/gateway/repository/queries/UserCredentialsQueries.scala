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

final class UserCredentialsQueries(
    config: RepositoryConfig
) {

  private val frSchema               = Fragment.const(config.schema)
  private val frUserCredentialsTable = Fragment.const(config.userCredentialsTable)
  private val frTable                = frSchema ++ fr0"." ++ frUserCredentialsTable

  val frUserCredentialsFields =
    fr"""
        |user_id,
        |password_hash,
        |created_at,
        |updated_at
         """.stripMargin

  def insertUserCredentials(userCredentialsRow: UserCredentialsRow): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frUserCredentialsFields ++ fr")" ++
          fr"VALUES (" ++ fr"$userCredentialsRow" ++ fr")"

      q.update.run.void
    }

  def updateUserCredentials(
      userID: UserID,
      passwordHashUpdate: PasswordHash,
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
    tzio {
      val q =
        fr"UPDATE" ++ frTable ++
          set(
            NonEmptyList.of(
              fr"password_hash = $passwordHashUpdate",
              fr"updated_at = $updatedAt",
            )
          ) ++
          whereAnd(fr"user_id = $userID")

      q.update.run.void
    }

  def getUserCredentials(userID: UserID): TranzactIO[Option[UserCredentialsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserCredentialsFields ++
          fr"FROM" ++ frTable ++
          whereAnd(fr"user_id = $userID")

      q.query[UserCredentialsRow].option
    }

  // Testing
  def getAllUserCredentialsTesting: TranzactIO[List[UserCredentialsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserCredentialsFields ++
          fr"FROM" ++ frTable

      q.query[UserCredentialsRow].to[List]
    }
}

object UserCredentialsQueries {

  val live = ZLayer.derive[UserCredentialsQueries]
}
