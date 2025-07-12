package io.rikkos.gateway.repository

import cats.syntax.all.*
import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.*
import io.rikkos.gateway.query.UserContactsQueries
import io.rikkos.generator.IDGenerator
import io.scalaland.chimney.dsl.*
import zio.*
import zio.interop.catz.*

trait UserContactsRepository {
  def upsertUserContacts(userID: UserID, upsertUserContacts: Vector[UpsertUserContact]): UIO[Unit]
}

object UserContactsRepository {

  final case class UserContactsPostgreSql(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends UserContactsRepository {

    override def upsertUserContacts(userID: UserID, upsertUserContacts: Vector[UpsertUserContact]): UIO[Unit] =
      (for {
        now <- timeProvider.instantNow
        userContactsTable <- upsertUserContacts.traverse(upsertUserContact =>
          idGenerator.generate
            .map(UserContactID.applyUnsafe)
            .map(userContactID =>
              upsertUserContact
                .into[UserContactTable]
                .withFieldComputed(_.userContactID, _.userContactID.getOrElse(userContactID))
                .withFieldConst(_.userID, userID)
                .withFieldConst(_.createdAt, CreatedAt(now))
                .withFieldConst(_.updatedAt, UpdatedAt(now))
                .transform
            )
        )
        _ <- database
          .transactionOrDie(UserContactsQueries.upsertUserContacts(userContactsTable))
      } yield ()).orDie
//      (for {
//        instantNow <- timeProvider.instantNow
//        _ <- database
//          .transactionOrDie(
//            UserDetailsQueries.insertUserDetailsQuery(
//              onboardUserDetails
//                .into[UserDetailsTable]
//                .withFieldConst(_.userID, userID)
//                .withFieldConst(_.email, email)
//                .withFieldConst(_.createdAt, CreatedAt(instantNow))
//                .withFieldConst(_.updatedAt, UpdatedAt(instantNow))
//                .transform
//            )
//          )
//      } yield ()).orDie.catchSomeCause {
//        case Cause.Die(DbException.Wrapped(e: PSQLException), _) if e.getSQLState == "23505" =>
//          ZIO.fail(ServiceError.ConflictError.UserAlreadyExists(userID, email))
//      }
  }

  def observed(repository: UserContactsRepository): UserContactsRepository = repository

  val live = ZLayer.fromFunction(UserContactsPostgreSql.apply) >>> ZLayer.fromFunction(observed)
}
