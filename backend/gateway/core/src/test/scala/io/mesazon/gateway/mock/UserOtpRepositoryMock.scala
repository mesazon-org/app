package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserOtpRepository
import io.mesazon.gateway.repository.domain.UserOtpRow
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait UserOtpRepositoryMock extends ZIOTestOps, should.Matchers {
  private val upsertUserOtpCounterRef: Ref[Int]      = Ref.make(0).zioValue
  private val updateUserOtpCounterRef: Ref[Int]      = Ref.make(0).zioValue
  private val getUserOtpCounterRef: Ref[Int]         = Ref.make(0).zioValue
  private val getUserOtpByUserIDCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val deleteUserOtpCounterRef: Ref[Int]      = Ref.make(0).zioValue

  def checkUserOtpRepository(
      expectedUpsertUserOtpCalls: Int = 0,
      expectedUpdateUserOtpCalls: Int = 0,
      expectedGetUserOtpCalls: Int = 0,
      expectedGetUserOtpByUserIDCalls: Int = 0,
      expectedDeleteUserOtpCalls: Int = 0,
  ): Assertion = {
    upsertUserOtpCounterRef.get.zioValue shouldBe expectedUpsertUserOtpCalls
    updateUserOtpCounterRef.get.zioValue shouldBe expectedUpdateUserOtpCalls
    getUserOtpCounterRef.get.zioValue shouldBe expectedGetUserOtpCalls
    getUserOtpByUserIDCounterRef.get.zioValue shouldBe expectedGetUserOtpByUserIDCalls
    deleteUserOtpCounterRef.get.zioValue shouldBe expectedDeleteUserOtpCalls
  }

  def userOtpRepositoryMockLive(
      userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ): ULayer[UserOtpRepository] = ZLayer.succeed(
    new UserOtpRepository {

      override def upsertUserOtp(
          userID: UserID,
          otp: Otp,
          otpType: OtpType,
          expiresAt: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        upsertUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold[IO[ServiceError, UserOtpRow]](
            ZIO.succeed(
              userOtpRows.values.find(_.userID == userID).get
            )
          )(ZIO.fail(_).orDie)
        )(ZIO.fail(_))

      override def getUserOtp(otpID: OtpID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(
              userOtpRows.get(otpID).filter(_.otpType == otpType)
            )
          )(ZIO.fail(_).orDie)
        )(ZIO.fail(_))

      override def getUserOtpByUserID(userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpByUserIDCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(
              userOtpRows.values.find(row => row.userID == userID && row.otpType == otpType)
            )
          )(ZIO.fail(_).orDie)
        )(ZIO.fail(_))

      override def updateUserOtp(
          otpID: OtpID,
          userID: UserID,
          otpType: OtpType,
          expiresAtUpdate: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        updateUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold[IO[ServiceError, UserOtpRow]](
            ZIO.succeed(
              userOtpRows(otpID)
            )
          )(ZIO.fail(_).orDie)
        )(ZIO.fail(_))

      override def deleteUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Unit] =
        deleteUserOtpCounterRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold[IO[ServiceError, Unit]](
            ZIO.unit
          )(ZIO.fail(_).orDie)
        )(ZIO.fail(_))
    }
  )
}
