package io.mesazon.gateway.repository.queries

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

final class UserTokenQueries(
    config: RepositoryConfig
) {

  private val frSchema          = Fragment.const(config.schema)
  private val frUserTokensTable = Fragment.const(config.userTokenTable)
  private val frTable           = frSchema ++ fr0"." ++ frUserTokensTable

  val frUserTokensFields =
    fr"""
        |token_id,
        |user_id,
        |token_type,
        |created_at,
        |expires_at
         """.stripMargin

  def insertUserToken(userTokenRow: UserTokenRow): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frUserTokensFields ++ fr")" ++
          fr"VALUES (" ++ fr"$userTokenRow" ++ fr")"

      q.update.run.void
    }

  def getUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): TranzactIO[Option[UserTokenRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserTokensFields ++
          fr"FROM" ++ frTable ++
          whereAnd(
            fr"token_id = $tokenID",
            fr"user_id = $userID",
            fr"token_type = $tokenType",
          )

      q.query[UserTokenRow].option
    }

  def deleteUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): TranzactIO[Unit] =
    tzio {
      val q =
        fr"DELETE FROM" ++ frTable ++
          whereAnd(
            fr"token_id = $tokenID",
            fr"user_id = $userID",
            fr"token_type = $tokenType",
          ) ++
          fr"RETURNING 1"

      q.query[Int].unique.void
    }

  def deleteAllUserTokensForUser(userID: UserID): TranzactIO[Unit] =
    tzio {
      val q =
        fr"DELETE FROM" ++ frTable ++
          whereAnd(fr"user_id = $userID")

      q.update.run.void
    }

  // Testing
  def getAllUserTokensTesting: TranzactIO[List[UserTokenRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserTokensFields ++
          fr"FROM" ++ frTable

      q.query[UserTokenRow].to[List]
    }
}

object UserTokenQueries {
  val live = ZLayer.derive[UserTokenQueries]
}
