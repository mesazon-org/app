package io.mesazon.gateway.repository

import _root_.doobie.*
import io.github.gaelrenoux.tranzactio.{DatabaseOps, DbException}
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.{UserDetailsRow, UserOnboardRow}
import io.mesazon.gateway.repository.queries.UserManagementQueries
import io.mesazon.generator.IDGenerator
import io.scalaland.chimney.dsl.*
import org.postgresql.util.PSQLException
import zio.*

trait UserManagementRepository {
  def insertUserOnboardEmail(
      email: Email,
      stage: OnboardStage,
  ): IO[ServiceError.ConflictError.UserAlreadyExists, UserOnboardRow]

  def updateUserOnboard(
      userID: UserID,
      fullName: Option[FullName],
      phoneNumber: Option[PhoneNumberE164],
      passwordHash: Option[PasswordHash],
      stage: OnboardStage,
  ): UIO[Unit]

  def getUserOnboard(userID: UserID): IO[ServiceError.InternalServerError.UserNotFoundError, UserOnboardRow]

  def getUserOnboardByEmail(email: Email): IO[ServiceError.InternalServerError.UserNotFoundError, UserOnboardRow]

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

object UserManagementRepository {

  private final class UserManagementRepositoryPostgres(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
      userManagementQueries: UserManagementQueries,
  ) extends UserManagementRepository {

    override def insertUserOnboardEmail(
        email: Email,
        stage: OnboardStage,
    ): IO[ServiceError.ConflictError.UserAlreadyExists, UserOnboardRow] =
      for {
        instantNow <- timeProvider.instantNow
        userID     <- idGenerator.generate.map(UserID.assume)
        onboardUserRow = UserOnboardRow(
          userID = userID,
          email = email,
          fullName = None,
          passwordHash = None,
          phoneNumber = None,
          stage = stage,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        _ <- database
          .transactionOrDie(
            userManagementQueries.insertUserOnboard(onboardUserRow)
          )
          .orDie
          .catchSomeCause {
            case Cause.Die(DbException.Wrapped(e: PSQLException), _) if e.getSQLState == "23505" =>
              ZIO.fail(ServiceError.ConflictError.UserAlreadyExists(userID, email))
          }
      } yield onboardUserRow

    override def updateUserOnboard(
        userID: UserID,
        fullName: Option[FullName],
        phoneNumber: Option[PhoneNumberE164],
        passwordHash: Option[PasswordHash],
        stage: OnboardStage,
    ): UIO[Unit] = for {
      instantNow <- timeProvider.instantNow
      _          <- database
        .transactionOrDie(
          userManagementQueries
            .updateOnboardUser(userID, fullName, phoneNumber, passwordHash, stage, UpdatedAt(instantNow))
        )
        .orDie
    } yield ()

    override def insertUserDetails(
        userID: UserID,
        email: Email,
        onboardUserDetails: OnboardUserDetails,
    ): IO[ServiceError.ConflictError.UserAlreadyExists, Unit] =
      (for {
        instantNow <- timeProvider.instantNow
        _          <- database
          .transactionOrDie(
            userManagementQueries.insertUserDetailsQuery(
              onboardUserDetails
                .into[UserDetailsRow]
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
        _          <- database
          .transactionOrDie(
            userManagementQueries.updateUserDetailsQuery(
              updateUserDetails
                .into[userManagementQueries.UpdateUserDetailsQuery]
                .withFieldConst(_.userID, userID)
                .withFieldConst(_.updatedAt, UpdatedAt(instantNow))
                .transform
            )
          )
      } yield ()).orDie

    override def getUserOnboard(
        userID: UserID
    ): IO[ServiceError.InternalServerError.UserNotFoundError, UserOnboardRow] =
      for {
        maybeUserOnboardRow <- database.transactionOrDie(userManagementQueries.getOnboardUser(userID)).orDie
        userOnboardRow      <- ZIO.getOrFailWith(
          ServiceError.InternalServerError.UserNotFoundError(s"UserOnboard with userId [$userID] couldn't be found")
        )(maybeUserOnboardRow)
      } yield userOnboardRow

    override def getUserOnboardByEmail(
        email: Email
    ): IO[ServiceError.InternalServerError.UserNotFoundError, UserOnboardRow] =
      for {
        maybeUserOnboardRow <- database.transactionOrDie(userManagementQueries.getOnboardUserByEmail(email)).orDie
        userOnboardRow      <- ZIO.getOrFailWith(
          ServiceError.InternalServerError.UserNotFoundError(s"UserOnboard with email [$email] couldn't be found")
        )(maybeUserOnboardRow)
      } yield userOnboardRow
  }

  private def observed(repository: UserManagementRepository): UserManagementRepository = repository

  val live = ZLayer.derive[UserManagementRepositoryPostgres] >>> ZLayer.fromFunction(observed)
}
