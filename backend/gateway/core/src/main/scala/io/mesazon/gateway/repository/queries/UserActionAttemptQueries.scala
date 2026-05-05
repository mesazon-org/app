package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

class UserActionAttemptQueries(
    config: RepositoryConfig
) {

  private val frSchema                 = Fragment.const(config.schema)
  private val frUserActionAttemptTable = Fragment.const(config.userActionAttemptTable)

  val userActionAttemptFields =
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
      for {
        userActionAttemptRowOldOpt <- sql"""
          SELECT $userActionAttemptFields
          FROM $frSchema.$frUserActionAttemptTable
          WHERE user_id = ${userActionAttemptRow.userID} AND action_attempt_type = ${userActionAttemptRow.actionAttemptType}
        """.query[UserActionAttemptRow].option
        userActionAttemptRowUpsert <- sql"""
        INSERT INTO $frSchema.$frUserActionAttemptTable AS t ($userActionAttemptFields)
        VALUES ($userActionAttemptRow)
        ON CONFLICT (user_id, action_attempt_type) DO UPDATE
        SET attempts = t.attempts + 1,
            updated_at = EXCLUDED.updated_at
        RETURNING $userActionAttemptFields
      """.stripMargin.query[UserActionAttemptRow].unique
      } yield userActionAttemptRowOldOpt.getOrElse(userActionAttemptRowUpsert)
    }

  def delete(userID: UserID, actionAttemptType: ActionAttemptType): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserActionAttemptTable
           |WHERE user_id = $userID AND action_attempt_type = $actionAttemptType
           |""".stripMargin.update.run
    }.unit

  // Testing
  def getAllUserActionAttemptsTesting: TranzactIO[List[UserActionAttemptRow]] =
    tzio {
      sql"""
           |SELECT $userActionAttemptFields
           |FROM $frSchema.$frUserActionAttemptTable
           |""".stripMargin.query[UserActionAttemptRow].to[List]
    }

  def insertUserActionAttemptTesting(userActionAttemptRow: UserActionAttemptRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frUserActionAttemptTable ($userActionAttemptFields)
           |VALUES ($userActionAttemptRow)
           |""".stripMargin.update.run
    }.unit
}

object UserActionAttemptQueries {

  val live = ZLayer.derive[UserActionAttemptQueries]
}
