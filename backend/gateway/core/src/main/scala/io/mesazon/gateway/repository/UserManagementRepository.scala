package io.mesazon.gateway.repository

import _root_.doobie.*
import io.github.gaelrenoux.tranzactio.*
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.UserManagementQueries
import io.mesazon.generator.IDGenerator
import io.scalaland.chimney.dsl.*
import org.postgresql.util.PSQLException
import zio.*

trait UserManagementRepository {
  def insertUserOnboardEmail(
      email: Email,
      stage: OnboardStage,
  ): IO[ServiceError, UserOnboardRow]

  def updateUserOnboard(
      userID: UserID,
      stage: OnboardStage,
      fullName: Option[FullName] = None,
      phoneNumber: Option[PhoneNumberE164] = None,
      passwordHash: Option[PasswordHash] = None,
  ): IO[ServiceError, UserOnboardRow]

  def getUserOnboard(userID: UserID): IO[ServiceError, Option[UserOnboardRow]]

  def getUserOnboardByEmail(email: Email): IO[ServiceError, Option[UserOnboardRow]]

  def upsertUserOtp(
      userID: UserID,
      otp: Otp,
      otpType: OtpType,
      expiresAt: ExpiresAt,
  ): IO[ServiceError, UserOtpRow]

  def updateUserOtp(
      otpID: OtpID,
      expiresAt: ExpiresAt,
  ): IO[ServiceError, UserOtpRow]

  def deleteUserOtp(
      otpID: OtpID
  ): IO[ServiceError, Unit]

  def getUserOtp(
      otpID: OtpID
  ): IO[ServiceError, Option[UserOtpRow]]

  def getUserOtpByUserID(
      userID: UserID,
      otpType: OtpType,
  ): IO[ServiceError, Option[UserOtpRow]]

  def upsertUserRefreshToken(
      userID: UserID,
      tokenID: TokenID,
      expiresAt: ExpiresAt,
      maybeOldTokenID: Option[TokenID] = None,
  ): IO[ServiceError, Unit]

  def getUserRefreshToken(
      tokenID: TokenID
  ): IO[ServiceError, Option[UserRefreshTokenRow]]

  def deleteUserRefreshToken(
      tokenID: TokenID
  ): IO[ServiceError, Unit]

  def deleteAllUserRefreshTokens(
      userID: UserID
  ): IO[ServiceError, Unit]

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
    ): IO[ServiceError, UserOnboardRow] =
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
        insertedUserOnboardRow <- database
          .transactionOrDie(
            userManagementQueries.insertUserOnboardEmail(onboardUserRow)
          )
          .mapError(e =>
            ServiceError.InternalServerError
              .UnexpectedError(s"Failed to insertUserOnboardEmail: [$email], [$stage]", Some(e))
          )
      } yield insertedUserOnboardRow

    override def getUserOnboard(
        userID: UserID
    ): IO[ServiceError, Option[UserOnboardRow]] =
      database
        .transactionOrDie(userManagementQueries.getUserOnboard(userID))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(s"Failed to getUserOnboard: [$userID]", Some(e))
        )

    override def getUserOnboardByEmail(
        email: Email
    ): IO[ServiceError, Option[UserOnboardRow]] =
      database
        .transactionOrDie(userManagementQueries.getUserOnboardByEmail(email))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(s"Failed to getUserOnboardByEmail: [$email]", Some(e))
        )

    override def updateUserOnboard(
        userID: UserID,
        stage: OnboardStage,
        fullName: Option[FullName] = None,
        phoneNumber: Option[PhoneNumberE164] = None,
        passwordHash: Option[PasswordHash] = None,
    ): IO[ServiceError, UserOnboardRow] = for {
      instantNow     <- timeProvider.instantNow
      userOnboardRow <- database
        .transactionOrDie(
          userManagementQueries
            .updateUserOnboard(userID, fullName, phoneNumber, passwordHash, stage, UpdatedAt(instantNow))
        )
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to updateUserOnboard: [$userID], [$stage], [$fullName], [$phoneNumber]",
            Some(e),
          )
        )
    } yield userOnboardRow

    override def getUserOtpByUserID(userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
      database
        .transactionOrDie(userManagementQueries.getUserOtpByUserID(userID, otpType))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to getUserOtpByUserID: [$userID], [$otpType]",
            Some(e),
          )
        )

    override def upsertUserOtp(
        userID: UserID,
        otp: Otp,
        otpType: OtpType,
        expiresAt: ExpiresAt,
    ): IO[ServiceError, UserOtpRow] =
      for {
        instantNow <- timeProvider.instantNow
        otpID      <- idGenerator.generate.map(OtpID.assume)
        userOtpRow = UserOtpRow(
          otpID = otpID,
          userID = userID,
          otp = otp,
          otpType = otpType,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
          expiresAt = expiresAt,
        )
        upsertUserOptRow <- database
          .transactionOrDie(userManagementQueries.upsertUserOtp(userOtpRow))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to upsertUserOtp: [$userID], [$otp], [$otpType], [$expiresAt]",
              Some(e),
            )
          )
      } yield upsertUserOptRow

    def updateUserOtp(
        otpID: OtpID,
        expiresAt: ExpiresAt,
    ): IO[ServiceError, UserOtpRow] =
      for {
        instantNow        <- timeProvider.instantNow
        updatedUserOptRow <- database
          .transactionOrDie(userManagementQueries.updateUserOtp(otpID, expiresAt, UpdatedAt(instantNow)))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to updateUserOtp: [$otpID], [$expiresAt]",
              Some(e),
            )
          )
      } yield updatedUserOptRow

    override def deleteUserOtp(otpID: OtpID): IO[ServiceError, Unit] =
      database
        .transactionOrDie(userManagementQueries.deleteUserOtp(otpID))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to deleteUserOtp: [$otpID]",
            Some(e),
          )
        )
        .unit

    override def getUserOtp(otpID: OtpID): IO[ServiceError, Option[UserOtpRow]] =
      database
        .transactionOrDie(userManagementQueries.getUserOtp(otpID))
        .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to getUserOtp: [$otpID]", Some(e)))

    override def upsertUserRefreshToken(
        userID: UserID,
        tokenID: TokenID,
        expiresAt: ExpiresAt,
        maybeOldTokenID: Option[TokenID],
    ): IO[ServiceError, Unit] =
      for {
        instantNow <- timeProvider.instantNow
        userRefreshTokenRow = UserRefreshTokenRow(
          tokenID = tokenID,
          userID = userID,
          createdAt = CreatedAt(instantNow),
          expiresAt = expiresAt,
        )
        _ <- database
          .transactionOrDie(userManagementQueries.upsertUserRefreshToken(userRefreshTokenRow, maybeOldTokenID))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to upsertUserRefreshToken: [$userID], [$tokenID], [$maybeOldTokenID]",
              Some(e),
            )
          )
      } yield ()

    override def getUserRefreshToken(tokenID: TokenID): IO[ServiceError, Option[UserRefreshTokenRow]] =
      database
        .transactionOrDie(userManagementQueries.getUserRefreshToken(tokenID))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to getUserRefreshToken: [$tokenID]",
            Some(e),
          )
        )

    override def deleteUserRefreshToken(tokenID: TokenID): IO[ServiceError, Unit] =
      database
        .transactionOrDie(userManagementQueries.deleteUserRefreshToken(tokenID))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to deleteUserRefreshToken: [$tokenID]",
            Some(e),
          )
        )
        .unit

    override def deleteAllUserRefreshTokens(userID: UserID): IO[ServiceError, Unit] =
      database
        .transactionOrDie(userManagementQueries.deleteAllUserRefreshTokens(userID))
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to deleteAllUserRefreshTokens: [$userID]",
            Some(e),
          )
        )
        .unit

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
  }

  private def observed(repository: UserManagementRepository): UserManagementRepository = repository

  val live = ZLayer.derive[UserManagementRepositoryPostgres] >>> ZLayer.fromFunction(observed)
}
