package io.rikkos.gateway.repository.queries

import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.fragment.Fragment
import io.github.gaelrenoux.tranzactio.doobie.*
import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha
import io.rikkos.gateway.repository.domain.*
import zio.*

final class WahaQueries(
    schema: String,
    wahaUsersTable: String,
    wahaUserActivityTable: String,
    wahaUserMessagesTable: String,
) {

  private val frSchema                = Fragment.const(schema)
  private val frWahaUsersTable        = Fragment.const(wahaUsersTable)
  private val frWahaUserActivityTable = Fragment.const(wahaUserActivityTable)
  private val frWahaUserMessagesTable = Fragment.const(wahaUserMessagesTable)

  private val wahaUserFields =
    fr"""user_id,
        |waha_user_id,
        |waha_user_account_id,
        |waha_chat_id,
        |phone_number,
        |created_at,
        |updated_at
      """.stripMargin

  private val wahaUserActivityFields =
    fr"""user_id,
        |is_waiting_assistant_reply,
        |last_update
      """.stripMargin

  private val wahaUserMessagesFields =
    fr"""user_id,
        |message_id,
        |message,
        |is_assistant,
        |created_at
      """.stripMargin

  def createOrGetWahaUserRow(row: WahaUserRow): TranzactIO[UserID] =
    tzio {
      sql"""
        |INSERT INTO $frSchema.$frWahaUsersTable (
        | $wahaUserFields
        |)
        |VALUES ($row)
        |ON CONFLICT (user_id)
        |DO UPDATE SET user_id = $frWahaUsersTable.user_id
        |RETURNING user_id
      """.stripMargin.query[UserID].unique
    }

  def getWahaUserRow(userID: UserID): TranzactIO[Option[WahaUserRow]] =
    tzio {
      sql"""
           |SELECT $wahaUserFields
           |FROM $frSchema.$frWahaUsersTable
           |WHERE user_id = $userID
      """.stripMargin.query[WahaUserRow].option
    }

  def getWahaUserWithWahaUserId(wahaUserID: waha.UserID): TranzactIO[Option[WahaUserRow]] =
    tzio {
      sql"""
           |SELECT $wahaUserFields
           |FROM $frSchema.$frWahaUsersTable
           |WHERE waha_user_id = $wahaUserID
      """.stripMargin.query[WahaUserRow].option
    }

  def getAllWahaUsers: TranzactIO[Vector[WahaUserRow]] =
    tzio {
      sql"""
        |SELECT $wahaUserFields
        |FROM $frSchema.$frWahaUsersTable
      """.stripMargin.query[WahaUserRow].to[Vector]
    }

  def upsertWahaUserActivityRow(row: WahaUserActivityRow): TranzactIO[Int] =
    tzio {
      sql"""
        |INSERT INTO $frSchema.$frWahaUserActivityTable (
        | $wahaUserActivityFields
        |)
        |VALUES ($row)
        |ON CONFLICT (user_id) DO UPDATE SET
        |is_waiting_assistant_reply = EXCLUDED.is_waiting_assistant_reply,
        |last_update = EXCLUDED.last_update
      """.stripMargin.update.run
    }

  def getWahaUserActivityRow(userID: UserID): TranzactIO[Option[WahaUserActivityRow]] =
    tzio {
      sql"""
        |SELECT $wahaUserActivityFields
        |FROM $frSchema.$frWahaUserActivityTable
        |WHERE user_id = $userID
      """.stripMargin.query[WahaUserActivityRow].option
    }

  def getAllWahaUserActivities: TranzactIO[Vector[WahaUserActivityRow]] =
    tzio {
      sql"""
        |SELECT $wahaUserActivityFields
        |FROM $frSchema.$frWahaUserActivityTable
      """.stripMargin.query[WahaUserActivityRow].to[Vector]
    }

  def insertWahaUserMessageRow(row: WahaUserMessageRow): TranzactIO[Int] =
    tzio {
      sql"""
         |INSERT INTO $frSchema.$frWahaUserMessagesTable (
         | $wahaUserMessagesFields
         |)
         |VALUES ($row)
         |ON CONFLICT DO NOTHING
      """.stripMargin.update.run
    }

  def getWahaUserMessages(userID: UserID): TranzactIOStream[WahaUserMessageRow] =
    tzioStream {
      sql"""
         |SELECT $wahaUserMessagesFields
         |FROM $frSchema.$frWahaUserMessagesTable
         |WHERE user_id = $userID
         |ORDER BY created_at ASC
      """.stripMargin.query[WahaUserMessageRow].stream
    }

  def getAllWahaUserMessages: TranzactIO[Vector[WahaUserMessageRow]] =
    tzio {
      sql"""
        |SELECT $wahaUserMessagesFields
        |FROM $frSchema.$frWahaUserMessagesTable
      """.stripMargin.query[WahaUserMessageRow].to[Vector]
    }
}

object WahaQueries {

  def live(schema: String): ULayer[WahaQueries] = ZLayer.succeed(
    new WahaQueries(
      schema = schema,
      wahaUsersTable = "waha_users",
      wahaUserActivityTable = "waha_user_activity",
      wahaUserMessagesTable = "waha_user_messages",
    )
  )
}
