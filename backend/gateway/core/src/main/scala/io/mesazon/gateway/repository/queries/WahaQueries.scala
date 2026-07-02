package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.fragments.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.{WahaUserActivityRow, WahaUserMessageRow, WahaUserRow}
import zio.*

final class WahaQueries(
    config: RepositoryConfig
) {

  private val frSchema                    = Fragment.const(config.schema)
  private val frWahaUsersTableName        = Fragment.const(config.wahaUserTable)
  private val frWahaUserActivityTableName = Fragment.const(config.wahaUserActivityTable)
  private val frWahaUserMessagesTableName = Fragment.const(config.wahaUserMessageTable)
  private val frWahaUsersTable            = frSchema ++ fr0"." ++ frWahaUsersTableName
  private val frWahaUserActivityTable     = frSchema ++ fr0"." ++ frWahaUserActivityTableName
  private val frWahaUserMessagesTable     = frSchema ++ fr0"." ++ frWahaUserMessagesTableName

  private val frWahaUserFields =
    fr"""
        |user_id,
        |full_name,
        |waha_user_id,
        |waha_user_account_id,
        |waha_chat_id,
        |phone_number,
        |created_at,
        |updated_at
         """.stripMargin

  private val frWahaUserActivityFields =
    fr"""
        |user_id,
        |last_message_id,
        |is_waiting_assistant_reply,
        |last_update
         """.stripMargin

  private val frWahaUserMessagesFields =
    fr"""
        |user_id,
        |message_id,
        |message,
        |is_assistant,
        |created_at
         """.stripMargin

  def createOrGetWahaUserRow(row: WahaUserRow): TranzactIO[WahaUserRow] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frWahaUsersTable ++
          fr"(" ++ frWahaUserFields ++ fr")" ++
          fr"VALUES (" ++ fr"$row" ++ fr")" ++
          fr"ON CONFLICT (waha_user_id) DO UPDATE" ++
          set(
            NonEmptyList.of(
              fr"full_name =" ++ frWahaUsersTableName ++ fr0".full_name"
            )
          ) ++
          fr"RETURNING" ++ frWahaUserFields

      q.query[WahaUserRow].unique
    }

  def getWahaUserRow(userID: UserID): TranzactIO[Option[WahaUserRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frWahaUserFields ++
          fr"FROM" ++ frWahaUsersTable ++
          whereAnd(fr"user_id = $userID")

      q.query[WahaUserRow].option
    }

  def getWahaUserWithWahaUserId(wahaUserID: waha.UserID): TranzactIO[Option[WahaUserRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frWahaUserFields ++
          fr"FROM" ++ frWahaUsersTable ++
          whereAnd(fr"waha_user_id = $wahaUserID")

      q.query[WahaUserRow].option
    }

  def getAllWahaUsers: TranzactIO[Vector[WahaUserRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frWahaUserFields ++
          fr"FROM" ++ frWahaUsersTable

      q.query[WahaUserRow].to[Vector]
    }

  def upsertWahaUserActivityForceRow(row: WahaUserActivityRow): TranzactIO[Int] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frWahaUserActivityTable ++
          fr"(" ++ frWahaUserActivityFields ++ fr")" ++
          fr"VALUES (" ++ fr"$row" ++ fr")" ++
          fr"ON CONFLICT (user_id) DO UPDATE" ++
          set(
            NonEmptyList.of(
              fr"is_waiting_assistant_reply = EXCLUDED.is_waiting_assistant_reply",
              fr"last_update = EXCLUDED.last_update",
              fr"last_message_id = COALESCE(EXCLUDED.last_message_id," ++ frWahaUserActivityTableName ++ fr0".last_message_id)",
            )
          )

      q.update.run
    }

  def upsertWahaUserActivityNonForceRow(row: WahaUserActivityRow): TranzactIO[Int] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frWahaUserActivityTable ++
          fr"(" ++ frWahaUserActivityFields ++ fr")" ++
          fr"VALUES (" ++ fr"$row" ++ fr")" ++
          fr"ON CONFLICT (user_id) DO UPDATE" ++
          set(
            NonEmptyList.of(
              fr"is_waiting_assistant_reply = EXCLUDED.is_waiting_assistant_reply",
              fr"last_update = EXCLUDED.last_update",
              fr"last_message_id = COALESCE(EXCLUDED.last_message_id," ++ frWahaUserActivityTableName ++ fr0".last_message_id)",
            )
          ) ++
          fr"WHERE" ++ frWahaUserActivityTableName ++ fr0".last_message_id = EXCLUDED.last_message_id"

      q.update.run
    }

  def getWahaUserActivityRow(userID: UserID): TranzactIO[Option[WahaUserActivityRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frWahaUserActivityFields ++
          fr"FROM" ++ frWahaUserActivityTable ++
          whereAnd(fr"user_id = $userID")

      q.query[WahaUserActivityRow].option
    }

  def getAllWahaUserActivities: TranzactIO[Vector[WahaUserActivityRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frWahaUserActivityFields ++
          fr"FROM" ++ frWahaUserActivityTable

      q.query[WahaUserActivityRow].to[Vector]
    }

  def getWahaUserActivitiesRowWaitingForAssistantReply: TranzactIOStream[WahaUserActivityRow] =
    tzioStream {
      val q =
        fr"SELECT" ++ frWahaUserActivityFields ++
          fr"FROM" ++ frWahaUserActivityTable ++
          whereAnd(fr"is_waiting_assistant_reply = true") ++
          orderBy(fr"last_update ASC")

      q.query[WahaUserActivityRow].stream
    }

  def insertWahaUserMessageRow(row: WahaUserMessageRow): TranzactIO[Int] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frWahaUserMessagesTable ++
          fr"(" ++ frWahaUserMessagesFields ++ fr")" ++
          fr"VALUES (" ++ fr"$row" ++ fr")" ++
          fr"ON CONFLICT DO NOTHING"

      q.update.run
    }

  def getWahaUserMessages(userID: UserID): TranzactIOStream[WahaUserMessageRow] =
    tzioStream {
      val q =
        fr"SELECT" ++ frWahaUserMessagesFields ++
          fr"FROM" ++ frWahaUserMessagesTable ++
          whereAnd(fr"user_id = $userID") ++
          orderBy(fr"created_at DESC")

      q.query[WahaUserMessageRow].stream
    }

  def getAllWahaUserMessages: TranzactIO[Vector[WahaUserMessageRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frWahaUserMessagesFields ++
          fr"FROM" ++ frWahaUserMessagesTable

      q.query[WahaUserMessageRow].to[Vector]
    }
}

object WahaQueries {

  val live = ZLayer.derive[WahaQueries]
}
