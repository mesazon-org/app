package io.mesazon.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.generator.IDGenerator
import zio.*

trait UserActionAttemptRepository {
  def getAndIncreaseUserActionAttempt(
      userID: UserID,
      actionAttemptType: ActionAttemptType,
  ): IO[ServiceError, UserActionAttemptRow]

  def deleteUserActionAttempt(
      userID: UserID,
      actionAttemptType: ActionAttemptType,
  ): IO[ServiceError, Unit]
}

object UserActionAttemptRepository {

  private final class UserActionAttemptRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      userActionAttemptQueries: UserActionAttemptQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends UserActionAttemptRepository {

    private lazy val attemptsDefault =
      Attempts.applyUnsafe(1) // Starting with 1 attempt when there is no existing record

    override def getAndIncreaseUserActionAttempt(
        userID: UserID,
        actionAttemptType: ActionAttemptType,
    ): IO[ServiceError, UserActionAttemptRow] = for {
      instantNow      <- timeProvider.instantNow
      actionAttemptID <- idGenerator.generate
        .map(ActionAttemptID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError(s"Failed to construct actionAttemptID: [$e]")
            )
        )
      userActionAttemptRow = UserActionAttemptRow(
        actionAttemptID,
        userID,
        actionAttemptType,
        attemptsDefault,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
      )
      userActionAttemptRowUpdated <- database
        .transactionOrWiden(
          userActionAttemptQueries
            .getAndIncrease(userActionAttemptRow)
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(
            s"Failed to get and increase user action attempt for user ID: [$userID] and action attempt type: [$actionAttemptType]",
            e,
          )
        )
    } yield userActionAttemptRowUpdated

    override def deleteUserActionAttempt(
        userID: UserID,
        actionAttemptType: ActionAttemptType,
    ): IO[ServiceError, Unit] = database
      .transactionOrWiden(
        userActionAttemptQueries.delete(userID, actionAttemptType)
      )
      .mapError(e =>
        ServiceError.InternalServerError.DatabaseError(
          s"Failed to delete user action attempt for user ID: [$userID] and action attempt type: [$actionAttemptType]",
          e,
        )
      )

  }

  val live = ZLayer.derive[UserActionAttemptRepositoryImpl].project[UserActionAttemptRepository](identity)
}
