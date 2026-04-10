package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

class UserTokenQueries(
    config: RepositoryConfig
) {

  private val frSchema          = Fragment.const(config.schema)
  private val frUserTokensTable = Fragment.const(config.userTokenTable)

  val userTokensFields =
    fr"""
        |token_id,
        |user_id,
        |token_type,
        |created_at,
        |expires_at
      """.stripMargin

  def insertUserToken(userTokenRow: UserTokenRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frUserTokensTable ($userTokensFields)
           |VALUES ($userTokenRow)
           |ON CONFLICT DO NOTHING
           |""".stripMargin.update.run
    }.unit

  def getUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): TranzactIO[Option[UserTokenRow]] =
    tzio {
      sql"""
           |SELECT $userTokensFields
           |FROM $frSchema.$frUserTokensTable
           |WHERE token_id = $tokenID AND user_id = $userID AND token_type = $tokenType
           |""".stripMargin.query[UserTokenRow].option
    }

  def deleteUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserTokensTable
           |WHERE token_id = $tokenID AND user_id = $userID AND token_type = $tokenType
           |""".stripMargin.update.run
    }.unit

  def deleteAllUserTokensForUser(userID: UserID): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserTokensTable
           |WHERE user_id = $userID
           |""".stripMargin.update.run
    }.unit

  // Testing
  def getAllUserTokensTesting: TranzactIO[List[UserTokenRow]] =
    tzio {
      sql"""
           |SELECT $userTokensFields
           |FROM $frSchema.$frUserTokensTable
           |""".stripMargin.query[UserTokenRow].to[List]
    }
}

object UserTokenQueries {
  val live = ZLayer.derive[UserTokenQueries]
}
