package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.time.Instant
import java.util.UUID

trait UserManagementRepositoryMock extends ZIOTestOps, should.Matchers {
  private val insertUserOnboardEmailCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val updateUserOnboardCounterRef: Ref[Int]          = Ref.make(0).zioValue
  private val getUserOnboardCounterRef: Ref[Int]             = Ref.make(0).zioValue
  private val getUserOnboardByEmailCounterRef: Ref[Int]      = Ref.make(0).zioValue
  private val upsertUserOtpCounterRef: Ref[Int]              = Ref.make(0).zioValue
  private val deleteUserOtpCounterRef: Ref[Int]              = Ref.make(0).zioValue
  private val updateUserOtpCounterRef: Ref[Int]              = Ref.make(0).zioValue
  private val getUserOtpCounterRef: Ref[Int]                 = Ref.make(0).zioValue
  private val getUserOtpByUserIDCounterRef: Ref[Int]         = Ref.make(0).zioValue
  private val upsertUserRefreshTokenCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val getUserRefreshTokenCounterRef: Ref[Int]        = Ref.make(0).zioValue
  private val deleteUserRefreshTokenCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val deleteAllUserRefreshTokensCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val insertUserDetailsCounterRef: Ref[Int]          = Ref.make(0).zioValue
  private val updateUserDetailsCounterRef: Ref[Int]          = Ref.make(0).zioValue

  def checkUserManagementRepository(
      expectedInsertUserOnboardEmailCalls: Int = 0,
      expectedUpdateUserOnboardCalls: Int = 0,
      expectedGetUserOnboardCalls: Int = 0,
      expectedGetUserOnboardByEmailCalls: Int = 0,
      expectedUpsertUserOtpCalls: Int = 0,
      expectedDeleteUserOtpCalls: Int = 0,
      expectedUpdateUserOtpCalls: Int = 0,
      expectedGetUserOtpCalls: Int = 0,
      expectedGetUserOtpByUserIDCalls: Int = 0,
      expectedUpsertUserRefreshTokenCalls: Int = 0,
      expectedGetUserRefreshTokenCalls: Int = 0,
      expectedDeleteUserRefreshTokenCalls: Int = 0,
      expectedDeleteAllUserRefreshTokensCalls: Int = 0,
      expectedInsertUserDetailsCalls: Int = 0,
      expectedUpdateUserDetailsCalls: Int = 0,
  ): Assertion = {
    insertUserOnboardEmailCounterRef.get.zioValue shouldBe expectedInsertUserOnboardEmailCalls
    updateUserOnboardCounterRef.get.zioValue shouldBe expectedUpdateUserOnboardCalls
    getUserOnboardCounterRef.get.zioValue shouldBe expectedGetUserOnboardCalls
    getUserOnboardByEmailCounterRef.get.zioValue shouldBe expectedGetUserOnboardByEmailCalls
    upsertUserOtpCounterRef.get.zioValue shouldBe expectedUpsertUserOtpCalls
    deleteUserOtpCounterRef.get.zioValue shouldBe expectedDeleteUserOtpCalls
    updateUserOtpCounterRef.get.zioValue shouldBe expectedUpdateUserOtpCalls
    getUserOtpCounterRef.get.zioValue shouldBe expectedGetUserOtpCalls
    getUserOtpByUserIDCounterRef.get.zioValue shouldBe expectedGetUserOtpByUserIDCalls
    upsertUserRefreshTokenCounterRef.get.zioValue shouldBe expectedUpsertUserRefreshTokenCalls
    getUserRefreshTokenCounterRef.get.zioValue shouldBe expectedGetUserRefreshTokenCalls
    deleteUserRefreshTokenCounterRef.get.zioValue shouldBe expectedDeleteUserRefreshTokenCalls
    deleteAllUserRefreshTokensCounterRef.get.zioValue shouldBe expectedDeleteAllUserRefreshTokensCalls
    insertUserDetailsCounterRef.get.zioValue shouldBe expectedInsertUserDetailsCalls
    updateUserDetailsCounterRef.get.zioValue shouldBe expectedUpdateUserDetailsCalls
  }

  def userManagementRepositoryMockLive(
      userOnboardRows: Map[UserID, UserOnboardRow] = Map.empty,
      userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ) = ZLayer.succeed(
    new UserManagementRepository {

      override def insertUserOnboardEmail(
          email: Email,
          stage: OnboardStage,
      ): IO[ServiceError, UserOnboardRow] =
        insertUserOnboardEmailCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(
              UserOnboardRow(
                UserID.assume(UUID.randomUUID().toString),
                email,
                None,
                None,
                None,
                stage,
                CreatedAt.assume(Instant.now()),
                UpdatedAt.assume(Instant.now()),
              )
            )
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def updateUserOnboard(
          userID: UserID,
          stage: OnboardStage,
          fullName: Option[FullName],
          phoneNumber: Option[PhoneNumberE164],
          passwordHash: Option[PasswordHash],
      ): IO[ServiceError, UserOnboardRow] = updateUserOnboardCounterRef.incrementAndGet *> maybeServiceError.fold(
        maybeUnexpectedError.fold(
          ZIO.succeed(userOnboardRows(userID))
        )(ZIO.fail(_).orDie)
      )(ZIO.fail)

      override def getUserOnboard(userID: UserID): IO[ServiceError, Option[UserOnboardRow]] =
        getUserOnboardCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(userOnboardRows.get(userID))
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def getUserOnboardByEmail(email: Email): IO[ServiceError, Option[UserOnboardRow]] =
        getUserOnboardByEmailCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(userOnboardRows.values.find(_.email == email))
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def upsertUserOtp(
          userID: UserID,
          otp: Otp,
          otpType: OtpType,
          expiresAt: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        upsertUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(
              UserOtpRow(
                OtpID.assume(UUID.randomUUID().toString),
                userID,
                otp,
                otpType,
                CreatedAt.assume(Instant.now()),
                UpdatedAt.assume(Instant.now()),
                expiresAt,
              )
            )
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def deleteUserOtp(otpID: OtpID): IO[ServiceError, Unit] =
        deleteUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.unit
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      def updateUserOtp(
          otpID: OtpID,
          expiresAt: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] = updateUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
        maybeUnexpectedError.fold(
          ZIO.succeed(userOtpRows(otpID))
        )(ZIO.fail(_).orDie)
      )(ZIO.fail)

      override def getUserOtp(otpID: OtpID): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(userOtpRows.get(otpID))
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def getUserOtpByUserID(userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpByUserIDCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(userOtpRows.values.find(row => row.userID == userID && row.otpType == otpType))
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def insertUserDetails(
          userID: UserID,
          email: Email,
          userDetails: OnboardUserDetails,
      ): IO[ServiceError.ConflictError.UserAlreadyExists, Unit] =
        insertUserDetailsCounterRef.incrementAndGet *> maybeServiceError
          .map(_.asInstanceOf[ServiceError.ConflictError.UserAlreadyExists])
          .fold(
            maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit] =
        updateUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)

      override def upsertUserRefreshToken(
          userID: UserID,
          tokenID: TokenID,
          expiresAt: ExpiresAt,
          maybeOldTokenID: Option[TokenID],
      ): IO[ServiceError, Unit] =
        upsertUserRefreshTokenCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def deleteUserRefreshToken(tokenID: TokenID, userID: UserID): IO[ServiceError, Unit] =
        deleteUserRefreshTokenCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def deleteAllUserRefreshTokens(userID: UserID): IO[ServiceError, Unit] =
        deleteAllUserRefreshTokensCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)
        )(ZIO.fail)

      override def getUserRefreshToken(
          tokenID: TokenID,
          userID: UserID,
      ): IO[ServiceError, Option[UserRefreshTokenRow]] =
        getUserRefreshTokenCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(None)
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)
    }
  )
}
