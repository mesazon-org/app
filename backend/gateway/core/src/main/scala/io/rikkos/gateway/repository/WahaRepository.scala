package io.rikkos.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.{DatabaseOps, DbException}
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha
import io.rikkos.gateway.repository.domain.*
import io.rikkos.generator.IDGenerator
import zio.*
import zio.stream.*

import queries.WahaQueries

trait WahaRepository {
  def createOrGetWahaUser(
      wahaUserID: waha.UserID,
      wahaFullName: waha.FullName,
      wahaUserAccountID: waha.UserAccountID,
      wahaChatID: waha.ChatID,
      phoneNumber: PhoneNumberE164,
  ): IO[DbException, WahaUserRow]

  def getWahaUserWithWahaUserId(wahaUserID: waha.UserID): IO[DbException, Option[WahaUserRow]]

  def getWahaUser(userID: UserID): IO[DbException, Option[WahaUserRow]]

  def upsertWahaUserActivity(
      userID: UserID,
      lastMessageID: Option[waha.MessageID],
      isWaitingAssistantReply: Boolean,
      forceUpdate: Boolean,
  ): IO[DbException, Unit]

  def getUserWahaUserActivity(userID: UserID): IO[DbException, Option[WahaUserActivityRow]]

  def insertWahaUserMessage(
      userID: UserID,
      messageID: waha.MessageID,
      message: String,
      isAssistant: Boolean,
  ): IO[DbException, Unit]

  def getWahaUserMessages(userID: UserID): Stream[DbException, WahaUserMessageRow]

  def getWahaUsersActivityWaitingForAssistantReply: Stream[DbException, WahaUserActivityRow]
}

object WahaRepository {

  private class WahaRepositoryPostgres(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      queries: WahaQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends WahaRepository {

    override def createOrGetWahaUser(
        wahaUserID: waha.UserID,
        wahaFullName: waha.FullName,
        wahaUserAccountID: waha.UserAccountID,
        wahaChatID: waha.ChatID,
        phoneNumber: PhoneNumberE164,
    ): IO[DbException, WahaUserRow] =
      for {
        userID <- idGenerator.generate.map(UserID.applyUnsafe)
        now    <- timeProvider.instantNow
        row = WahaUserRow(
          userID = userID,
          fullName = wahaFullName,
          wahaUserID = wahaUserID,
          wahaAccountID = wahaUserAccountID,
          wahaChatID = wahaChatID,
          phoneNumber = phoneNumber,
          createdAt = CreatedAt(now),
          updatedAt = UpdatedAt(now),
        )
        row <- database.transactionOrDie(queries.createOrGetWahaUserRow(row))
      } yield row

    override def getWahaUserWithWahaUserId(wahaUserID: waha.UserID): IO[DbException, Option[WahaUserRow]] =
      database.transactionOrDie(queries.getWahaUserWithWahaUserId(wahaUserID))

    override def getWahaUser(userID: UserID): IO[DbException, Option[WahaUserRow]] =
      database.transactionOrDie(queries.getWahaUserRow(userID))

    override def upsertWahaUserActivity(
        userID: UserID,
        lastMessageID: Option[waha.MessageID],
        isWaitingAssistantReply: Boolean,
        forceUpdate: Boolean,
    ): IO[DbException, Unit] = for {
      now <- timeProvider.instantNow
      row = WahaUserActivityRow(
        userID = userID,
        lastMessageID = lastMessageID,
        isWaitingAssistantReply = isWaitingAssistantReply,
        lastUpdate = UpdatedAt(now),
      )
      _ <-
        if (forceUpdate) database.transactionOrDie(queries.upsertWahaUserActivityForceRow(row))
        else database.transactionOrDie(queries.upsertWahaUserActivityNonForceRow(row))
    } yield ()

    override def getUserWahaUserActivity(userID: UserID): IO[DbException, Option[WahaUserActivityRow]] =
      database.transactionOrDie(queries.getWahaUserActivityRow(userID))

    override def insertWahaUserMessage(
        userID: UserID,
        messageID: waha.MessageID,
        message: String,
        isAssistant: Boolean,
    ): IO[DbException, Unit] =
      for {
        now <- timeProvider.instantNow
        row = WahaUserMessageRow(
          userID = userID,
          messageID = messageID,
          message = message,
          isAssistant = isAssistant,
          createdAt = CreatedAt(now),
        )
        _ <- database.transactionOrDie(queries.insertWahaUserMessageRow(row))
      } yield ()

    override def getWahaUserMessages(userID: UserID): Stream[DbException, WahaUserMessageRow] =
      database.transactionOrDieStream(queries.getWahaUserMessages(userID))

    override def getWahaUsersActivityWaitingForAssistantReply: Stream[DbException, WahaUserActivityRow] =
      database.transactionOrDieStream(queries.getWahaUserActivitiesRowWaitingForAssistantReply)
  }

  private def observed(repository: WahaRepository): WahaRepository = repository

  val live = ZLayer.derive[WahaRepositoryPostgres] >>> ZLayer.fromFunction(observed)
}
