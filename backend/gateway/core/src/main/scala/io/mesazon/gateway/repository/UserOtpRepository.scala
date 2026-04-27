package io.mesazon.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.UserOtpRow
import io.mesazon.gateway.repository.queries.UserOtpQueries
import io.mesazon.generator.IDGenerator
import zio.*

trait UserOtpRepository {
  def upsertUserOtp(
      userID: UserID,
      otpType: OtpType,
      otp: Otp,
      expiresAt: ExpiresAt,
  ): IO[ServiceError, UserOtpRow]

  def getUserOtp(
      otpID: OtpID,
      userID: UserID,
      otpType: OtpType,
  ): IO[ServiceError, Option[UserOtpRow]]

  def getUserOtpByOtpID(
      otpID: OtpID,
      otpType: OtpType,
  ): IO[ServiceError, Option[UserOtpRow]]

  def getUserOtpByUserID(
      userID: UserID,
      otpType: OtpType,
  ): IO[ServiceError, Option[UserOtpRow]]

  def updateUserOtp(
      otpID: OtpID,
      userID: UserID,
      otpType: OtpType,
      expiresAtUpdate: ExpiresAt,
  ): IO[ServiceError, UserOtpRow]

  def deleteUserOtp(
      otpID: OtpID,
      userID: UserID,
      otpType: OtpType,
  ): IO[ServiceError, Unit]
}

object UserOtpRepository {

  private final class UserOtpRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      userOtpQueries: UserOtpQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends UserOtpRepository {

    override def upsertUserOtp(
        userID: UserID,
        otpType: OtpType,
        otp: Otp,
        expiresAt: ExpiresAt,
    ): IO[ServiceError, UserOtpRow] = for {
      instantNow <- timeProvider.instantNow
      otpID      <- idGenerator.generate
        .map(OtpID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct OTP ID: [$e]"))
        )
      userOtpRow = UserOtpRow(otpID, userID, otp, otpType, CreatedAt(instantNow), UpdatedAt(instantNow), expiresAt)
      _ <- database
        .transactionOrWiden(
          for {
            _           <- userOtpQueries.deleteUserOtp(userID, otpType)
            upsertedRow <- userOtpQueries.insertUserOtp(userOtpRow)
          } yield upsertedRow
        )
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(s"Failed to upsert user OTP: [$userID], [$otp], [$otpType], [$expiresAt]", e)
        )
    } yield userOtpRow

    override def updateUserOtp(
        otpID: OtpID,
        userID: UserID,
        otpType: OtpType,
        expiresAtUpdate: ExpiresAt,
    ): IO[ServiceError, UserOtpRow] =
      for {
        instantNow        <- timeProvider.instantNow
        updatedUserOtpRow <- database
          .transactionOrWiden(
            for {
              updatedUserOtpRow <- userOtpQueries.updateUserOtp(
                otpID,
                userID,
                otpType,
                expiresAtUpdate,
                UpdatedAt(instantNow),
              )
            } yield updatedUserOtpRow
          )
          .mapError(e =>
            ServiceError.InternalServerError
              .DatabaseError(s"Failed to update user OTP: [$otpID], [$userID], [$otpType], [$expiresAtUpdate]", e)
          )
      } yield updatedUserOtpRow

    override def getUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
      database
        .transactionOrWiden(
          userOtpQueries.getUserOtp(otpID, userID, otpType)
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(s"Failed to get user OTP: [$otpID], [$userID], [$otpType]", e)
        )

    override def getUserOtpByOtpID(
        otpID: OtpID,
        otpType: OtpType,
    ): IO[ServiceError, Option[UserOtpRow]] =
      database
        .transactionOrWiden(
          userOtpQueries.getUserOtpByOtpID(otpID, otpType)
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(s"Failed to get user OTP by OTP ID: [$otpID], [$otpType]", e)
        )

    override def getUserOtpByUserID(
        userID: UserID,
        otpType: OtpType,
    ): IO[ServiceError, Option[UserOtpRow]] =
      database
        .transactionOrWiden(
          userOtpQueries.getUserOtpByUserID(userID, otpType)
        )
        .mapError(e =>
          ServiceError.InternalServerError.DatabaseError(s"Failed to get user OTP by user ID: [$userID], [$otpType]", e)
        )

    def deleteUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Unit] =
      database
        .transactionOrWiden(
          userOtpQueries.deleteUserOtp(otpID, userID, otpType)
        )
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(s"Failed to delete user OTP: [$otpID], [$userID], [$otpType]", e)
        )
  }

  private def observed(userOtpRepository: UserOtpRepository): UserOtpRepository = userOtpRepository

  val live = ZLayer.derive[UserOtpRepositoryImpl] >>> ZLayer.fromFunction(observed)
}
