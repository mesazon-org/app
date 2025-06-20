package io.rikkos.gateway.repository

import _root_.doobie.*
import io.github.gaelrenoux.tranzactio.{DatabaseOps, DbException}
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.{CreatedAt, ServiceError, UpdatedAt, UserDetails}
import io.rikkos.gateway.query.UserDetailsQueries
import org.postgresql.util.PSQLException
import zio.*

trait UserRepository {
  def insertUserDetails(userDetails: UserDetails): IO[ServiceError.ConflictError.UserAlreadyExists, Unit]
}

object UserRepository {

  final private case class UserRepositoryPostgreSql(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      timeProvider: TimeProvider,
  ) extends UserRepository {

    override def insertUserDetails(userDetails: UserDetails): IO[ServiceError.ConflictError.UserAlreadyExists, Unit] =
      (for {
        instantNow <- timeProvider.instantNow
        _ <- database
          .transactionOrDie(
            UserDetailsQueries.insertUserDetailsQuery(
              userID = userDetails.userID,
              email = userDetails.email,
              firstName = userDetails.firstName,
              lastName = userDetails.lastName,
              countryCode = userDetails.countryCode,
              phoneNumber = userDetails.phoneNumber,
              addressLine1 = userDetails.addressLine1,
              addressLine2 = userDetails.addressLine2,
              city = userDetails.city,
              postalCode = userDetails.postalCode,
              company = userDetails.company,
              createdAt = CreatedAt.apply(instantNow),
              updatedAt = UpdatedAt.apply(instantNow),
            )
          )
      } yield ()).orDie.catchSomeCause {
        case Cause.Die(DbException.Wrapped(e: PSQLException), _) if e.getSQLState == "23505" =>
          ZIO.fail(ServiceError.ConflictError.UserAlreadyExists(userDetails.userID, userDetails.email))
      }
  }

  def observed(repository: UserRepository): UserRepository = repository

  val live = ZLayer.fromFunction(UserRepositoryPostgreSql.apply) >>> ZLayer.fromFunction(observed)
}
