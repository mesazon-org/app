package io.mesazon.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.*
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.generator.IDGenerator
import zio.*

trait UserDetailsRepository {
  def insertUserDetails(
      email: Email,
      onboardStage: OnboardStage,
  ): IO[ServiceError, UserDetailsRow]

  def updateUserDetails(
      userID: UserID,
      onboardStageUpdate: OnboardStage,
      fullNameOptUpdate: Option[FullName] = None,
      phoneNumberOptUpdate: Option[PhoneNumber] = None,
  ): IO[ServiceError, UserDetailsRow]

  def getUserDetails(userID: UserID): IO[ServiceError, Option[UserDetailsRow]]

  def getUserDetailsByEmail(email: Email): IO[ServiceError, Option[UserDetailsRow]]
}

object UserDetailsRepository {

  private final class UserDetailsRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
      userDetailsQueries: UserDetailsQueries,
  ) extends UserDetailsRepository {

    override def insertUserDetails(email: Email, onboardStage: OnboardStage): IO[ServiceError, UserDetailsRow] =
      for {
        instantNow <- timeProvider.instantNow
        userID     <- idGenerator.generate
          .map(UserID.either)
          .flatMap(
            ZIO
              .fromEither(_)
              .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct User ID: [$e]"))
          )
        userDetailsRow = UserDetailsRow(
          userID = userID,
          email = email,
          fullName = None,
          phoneNumber = None,
          onboardStage = onboardStage,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        _ <- database
          .transactionOrWiden(
            userDetailsQueries.insertUserDetails(userDetailsRow)
          )
          .mapError(e =>
            ServiceError.InternalServerError
              .DatabaseError(s"Failed to insertUserDetails: [$email], [$onboardStage]", e)
          )
      } yield userDetailsRow

    override def updateUserDetails(
        userID: UserID,
        onboardStageUpdate: OnboardStage,
        fullNameOptUpdate: Option[FullName],
        phoneNumberOptUpdate: Option[PhoneNumber],
    ): IO[ServiceError, UserDetailsRow] = for {
      instantNow     <- timeProvider.instantNow
      userDetailsRow <- database
        .transactionOrWiden(
          userDetailsQueries.updateUserDetails(
            userID,
            onboardStageUpdate,
            UpdatedAt(instantNow),
            fullNameOptUpdate,
            phoneNumberOptUpdate,
          )
        )
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(
              s"Failed to updateUserDetails: [$userID], [$onboardStageUpdate], [$fullNameOptUpdate], [$phoneNumberOptUpdate]",
              e,
            )
        )
    } yield userDetailsRow

    override def getUserDetails(userID: UserID): IO[ServiceError, Option[UserDetailsRow]] =
      database
        .transactionOrWiden(
          userDetailsQueries.getUserDetails(userID)
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(s"Failed to getUserDetails by user ID: [$userID]", e)
        )

    override def getUserDetailsByEmail(email: Email): IO[ServiceError, Option[UserDetailsRow]] =
      database
        .transactionOrWiden(
          userDetailsQueries.getUserDetailsByEmail(email)
        )
        .mapError(e => ServiceError.InternalServerError.DatabaseError(s"Failed to getUserDetailsByEmail: [$email]", e))
  }

  private def observed(repository: UserDetailsRepository): UserDetailsRepository = repository

  val live = ZLayer.derive[UserDetailsRepositoryImpl] >>> ZLayer.fromFunction(observed)
}
