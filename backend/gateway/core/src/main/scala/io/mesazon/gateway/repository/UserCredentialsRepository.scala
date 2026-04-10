package io.mesazon.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.UserCredentialsQueries
import zio.*

trait UserCredentialsRepository {
  def insertUserCredentials(
      userID: UserID,
      passwordHash: PasswordHash,
  ): IO[ServiceError, Unit]

  def getUserCredentials(userID: UserID): IO[ServiceError, Option[UserCredentialsRow]]

  def updateUserCredentials(
      userID: UserID,
      passwordHashUpdate: PasswordHash,
  ): IO[ServiceError, Unit]
}

object UserCredentialsRepository {

  private final class UserCredentialsRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      userCredentialsQueries: UserCredentialsQueries,
      timeProvider: TimeProvider,
  ) extends UserCredentialsRepository {

    override def insertUserCredentials(
        userID: UserID,
        passwordHash: PasswordHash,
    ): IO[ServiceError, Unit] = for {
      instantNow <- timeProvider.instantNow
      userCredentialsRow = UserCredentialsRow(userID, passwordHash, CreatedAt(instantNow), UpdatedAt(instantNow))
      _ <- database
        .transactionOrWiden(
          userCredentialsQueries.insertUserCredentials(userCredentialsRow)
        )
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(s"Failed to insert user credentials for user ID: [$userID]", e)
        )
    } yield ()

    override def getUserCredentials(userID: UserID): IO[ServiceError, Option[UserCredentialsRow]] =
      database
        .transactionOrWiden(
          userCredentialsQueries.getUserCredentials(userID)
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(s"Failed to get user credentials by user ID: [$userID]", e)
        )

    override def updateUserCredentials(
        userID: UserID,
        passwordHashUpdate: PasswordHash,
    ): IO[ServiceError, Unit] = for {
      instantNow <- timeProvider.instantNow
      _          <- database
        .transactionOrWiden(
          userCredentialsQueries.updateUserCredentials(userID, passwordHashUpdate, UpdatedAt(instantNow))
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(s"Failed to update user credentials for user ID: [$userID]", e)
        )
    } yield ()

  }

  private def observed(userCredentialsRepository: UserCredentialsRepository): UserCredentialsRepository =
    userCredentialsRepository

  val live = ZLayer.derive[UserCredentialsRepositoryImpl] >>> ZLayer.fromFunction(observed)
}
