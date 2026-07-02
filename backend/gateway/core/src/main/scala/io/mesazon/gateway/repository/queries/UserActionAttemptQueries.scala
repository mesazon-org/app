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

final class UserActionAttemptQueries(
    config: RepositoryConfig
) {

  private val frSchema                 = Fragment.const(config.schema)
  private val frUserActionAttemptTable = Fragment.const(config.userActionAttemptTable)
  private val frTable                  = frSchema ++ fr0"." ++ frUserActionAttemptTable

  val frUserActionAttemptFields =
    fr"""
        |action_attempt_id,
        |user_id,
        |action_attempt_type,
        |attempts,
        |created_at,
        |updated_at
         """.stripMargin

  def getAndIncrease(userActionAttemptRow: UserActionAttemptRow): TranzactIO[UserActionAttemptRow] =
    tzio {
      val select =
        fr"SELECT" ++ frUserActionAttemptFields ++
          fr"FROM" ++ frTable ++
          whereAnd(
            fr"user_id = ${userActionAttemptRow.userID}",
            fr"action_attempt_type = ${userActionAttemptRow.actionAttemptType}",
          )

      val upsert =
        fr"INSERT INTO" ++ frTable ++ fr"AS t (" ++ frUserActionAttemptFields ++ fr")" ++
          fr"VALUES (" ++ fr"$userActionAttemptRow" ++ fr")" ++
          fr"ON CONFLICT (user_id, action_attempt_type) DO UPDATE" ++
          set(
            NonEmptyList.of(
              fr"attempts = t.attempts + 1",
              fr"updated_at = EXCLUDED.updated_at",
            )
          ) ++
          fr"RETURNING" ++ frUserActionAttemptFields

      for {
        userActionAttemptRowOldOpt <- select.query[UserActionAttemptRow].option
        userActionAttemptRowUpsert <- upsert.query[UserActionAttemptRow].unique
      } yield userActionAttemptRowOldOpt.getOrElse(userActionAttemptRowUpsert)
    }

  def delete(userID: UserID, actionAttemptType: ActionAttemptType): TranzactIO[Unit] =
    tzio {
      val q =
        fr"DELETE FROM" ++ frTable ++
          whereAnd(
            fr"user_id = $userID",
            fr"action_attempt_type = $actionAttemptType",
          )

      q.update.run.void
    }

  // Testing
  def getAllUserActionAttemptsTesting: TranzactIO[List[UserActionAttemptRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserActionAttemptFields ++
          fr"FROM" ++ frTable

      q.query[UserActionAttemptRow].to[List]
    }

  def insertUserActionAttemptTesting(userActionAttemptRow: UserActionAttemptRow): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frUserActionAttemptFields ++ fr")" ++
          fr"VALUES (" ++ fr"$userActionAttemptRow" ++ fr")"

      q.update.run.void
    }
}

object UserActionAttemptQueries {

  val live = ZLayer.derive[UserActionAttemptQueries]
}
