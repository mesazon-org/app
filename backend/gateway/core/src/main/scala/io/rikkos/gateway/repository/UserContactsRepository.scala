package io.rikkos.gateway.repository

import cats.syntax.all.*
import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.github.gaelrenoux.tranzactio.doobie.Connection
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.*
import io.rikkos.gateway.query.UserContactsQueries
import io.rikkos.gateway.query.UserContactsQueries.UpdateUserContact
import io.rikkos.generator.IDGenerator
import io.scalaland.chimney.dsl.*
import zio.*
import zio.interop.catz.*

trait UserContactsRepository {
  def upsertUserContacts(userID: UserID, upsertUserContacts: NonEmptyChunk[UpsertUserContact]): UIO[Unit]
}

object UserContactsRepository {

  final case class UserContactsPostgreSql(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends UserContactsRepository {

    override def upsertUserContacts(userID: UserID, upsertUserContacts: NonEmptyChunk[UpsertUserContact]): UIO[Unit] =
      (for {
        now <- timeProvider.instantNow
        (updateUserContactsRaw, insertUserContactsRaw) = upsertUserContacts.partition(_.userContactID.nonEmpty)
        updateUserContacts = updateUserContactsRaw
          .map(updateUserContact =>
            updateUserContact.userContactID.map(userContactID =>
              updateUserContact
                .into[UpdateUserContact]
                .withFieldConst(_.userContactID, userContactID)
                .withFieldConst(_.updateAt, UpdatedAt(now))
                .transform
            )
          )
          .flatten
        insertUserContacts <- insertUserContactsRaw.traverse(insertUserContact =>
          idGenerator.generate
            .map(UserContactID.applyUnsafe)
            .map(userContactID =>
              insertUserContact
                .into[UserContactTable]
                .withFieldConst(_.userContactID, userContactID)
                .withFieldConst(_.userID, userID)
                .withFieldConst(_.createdAt, CreatedAt(now))
                .withFieldConst(_.updatedAt, UpdatedAt(now))
                .transform
            )
        )
        nonEmptyUpdateUserContacts = NonEmptyChunk.fromChunk(updateUserContacts)
        nonEmptyInsertUserContacts = NonEmptyChunk.fromChunk(insertUserContacts)
        updateUserContactsTrans = nonEmptyUpdateUserContacts
          .fold(ZIO.service[Connection].unit)(
            UserContactsQueries.updateUserContacts
          )
        insertUserContactsTrans = nonEmptyInsertUserContacts
          .fold(ZIO.service[Connection].unit)(
            UserContactsQueries.insertUserContacts
          )
        // IMPORTANT !!
        // Order of execution matters if a user performs an update on unique elements
        // (phone_number) and at the same time inserts a user with the conflicted phone_number
        // then if update executes first, users would not receive an error
        _ <- database
          .transactionOrDie(updateUserContactsTrans zip insertUserContactsTrans)
      } yield ()).orDie

  }

  def observed(repository: UserContactsRepository): UserContactsRepository = repository

  val live = ZLayer.fromFunction(UserContactsPostgreSql.apply) >>> ZLayer.fromFunction(observed)
}
