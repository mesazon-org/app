package io.rikkos.gateway.repository

import _root_.doobie.*
import io.github.gaelrenoux.tranzactio.{DatabaseOps, DbException}
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.*
import io.rikkos.gateway.query.UserDetailsQueries
import io.scalaland.chimney.dsl.*
import org.postgresql.util.PSQLException
import zio.*

trait UserRepository {
  def insertUserDetails(
      userID: UserID,
      email: Email,
      onboardUserDetails: OnboardUserDetails,
  ): IO[ServiceError.ConflictError.UserAlreadyExists, Unit]
  def updateUserDetails(
      userID: UserID,
      updateUserDetails: UpdateUserDetails,
  ): UIO[Unit]
}

object UserRepository {

  final private case class UserRepositoryPostgreSql(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      timeProvider: TimeProvider,
  ) extends UserRepository {

    override def insertUserDetails(
        userID: UserID,
        email: Email,
        onboardUserDetails: OnboardUserDetails,
    ): IO[ServiceError.ConflictError.UserAlreadyExists, Unit] =
      (for {
        instantNow <- timeProvider.instantNow
        _ <- database
          .transactionOrDie(
            UserDetailsQueries.insertUserDetailsQuery(
              onboardUserDetails
                .into[UserDetailsTable]
                .withFieldConst(_.userID, userID)
                .withFieldConst(_.email, email)
                .withFieldConst(_.createdAt, CreatedAt(instantNow))
                .withFieldConst(_.updatedAt, UpdatedAt(instantNow))
                .transform
            )
          )
      } yield ()).orDie.catchSomeCause {
        case Cause.Die(DbException.Wrapped(e: PSQLException), _) if e.getSQLState == "23505" =>
          ZIO.fail(ServiceError.ConflictError.UserAlreadyExists(userID, email))
      }

    override def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit] =
      (for {
        instantNow <- timeProvider.instantNow
        _ <- database
          .transactionOrDie(
            UserDetailsQueries.updateUserDetailsQuery(
              userID,
              updateUserDetails.firstName,
              updateUserDetails.lastName,
              updateUserDetails.phoneNumber,
              updateUserDetails.addressLine1,
              updateUserDetails.addressLine2,
              updateUserDetails.city,
              updateUserDetails.postalCode,
              updateUserDetails.company,
              UpdatedAt(instantNow),
            )
          )
      } yield ()).orDie
  }

  def observed(repository: UserRepository): UserRepository = repository

  val live = ZLayer.fromFunction(UserRepositoryPostgreSql.apply) >>> ZLayer.fromFunction(observed)
}
