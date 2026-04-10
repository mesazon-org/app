package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

class UserCredentialsQueries(
    config: RepositoryConfig
) {

  private val frSchema               = Fragment.const(config.schema)
  private val frUserCredentialsTable = Fragment.const(config.userCredentialsTable)

  val userCredentialsQueries =
    fr"""
        |user_id,
        |password_hash,
        |created_at,
        |updated_at
     """.stripMargin

  def insertUserCredentials(userCredentialsRow: UserCredentialsRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frUserCredentialsTable ($userCredentialsQueries)
           |VALUES ($userCredentialsRow)
           |""".stripMargin.update.run
    }.unit

  def updateUserCredentials(
      userID: UserID,
      passwordHashUpdate: PasswordHash,
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
    tzio {
      sql"""
           |UPDATE $frSchema.$frUserCredentialsTable
           |SET
           |  password_hash = $passwordHashUpdate,
           |  updated_at = $updatedAt
           |WHERE user_id = $userID
           |""".stripMargin.update.run
    }.unit

  def getUserCredentials(userID: UserID): TranzactIO[Option[UserCredentialsRow]] =
    tzio {
      sql"""
           |SELECT $userCredentialsQueries
           |FROM $frSchema.$frUserCredentialsTable
           |WHERE user_id = $userID
           |""".stripMargin.query[UserCredentialsRow].option
    }

  // Testing
  def getAllUserCredentialsTesting: TranzactIO[List[UserCredentialsRow]] =
    tzio {
      sql"""
           |SELECT $userCredentialsQueries
           |FROM $frSchema.$frUserCredentialsTable
           |""".stripMargin.query[UserCredentialsRow].to[List]
    }
}

object UserCredentialsQueries {

  val live = ZLayer.derive[UserCredentialsQueries]
}
