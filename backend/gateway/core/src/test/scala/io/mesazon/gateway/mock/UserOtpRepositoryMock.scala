package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserOtpRepository
import io.mesazon.gateway.repository.domain.UserOtpRow
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

trait UserOtpRepositoryMock extends ZIOTestOps, should.Matchers {
  private val upsertUserOtpCounterRef: Ref[Int]      = Ref.make(0).zioValue
  private val updateUserOtpCounterRef: Ref[Int]      = Ref.make(0).zioValue
  private val getUserOtpCounterRef: Ref[Int]         = Ref.make(0).zioValue
  private val getUserOtpByOtpIDCounterRef: Ref[Int]  = Ref.make(0).zioValue
  private val getUserOtpByUserIDCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val deleteUserOtpCounterRef: Ref[Int]      = Ref.make(0).zioValue

  def checkUserOtpRepository(
      expectedUpsertUserOtpCalls: Int = 0,
      expectedUpdateUserOtpCalls: Int = 0,
      expectedGetUserOtpCalls: Int = 0,
      expectedGetUserOtpByOtpIDCalls: Int = 0,
      expectedGetUserOtpByUserIDCalls: Int = 0,
      expectedDeleteUserOtpCalls: Int = 0,
  ): Assertion = {
    upsertUserOtpCounterRef.get.zioValue shouldBe expectedUpsertUserOtpCalls
    updateUserOtpCounterRef.get.zioValue shouldBe expectedUpdateUserOtpCalls
    getUserOtpCounterRef.get.zioValue shouldBe expectedGetUserOtpCalls
    getUserOtpByOtpIDCounterRef.get.zioValue shouldBe expectedGetUserOtpByOtpIDCalls
    getUserOtpByUserIDCounterRef.get.zioValue shouldBe expectedGetUserOtpByUserIDCalls
    deleteUserOtpCounterRef.get.zioValue shouldBe expectedDeleteUserOtpCalls
  }

  def userOtpRepositoryMockLive(
      userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
      serviceErrorOpt: Option[ServiceError] = None,
  ): ULayer[UserOtpRepository] = ZLayer.succeed(
    new UserOtpRepository {
      val atomicInteger = new AtomicInteger(0)

      override def upsertUserOtp(
          userID: UserID,
          otpType: OtpType,
          otp: Otp,
          expiresAt: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        upsertUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.succeed(
              UserOtpRow(
                otpID = OtpID.assume(s"otp-id-${atomicInteger.incrementAndGet()}"),
                userID = userID,
                otp = otp,
                otpType = otpType,
                createdAt = CreatedAt(Instant.now),
                updatedAt = UpdatedAt(Instant.now),
                expiresAt = expiresAt,
              )
            )
          )(ZIO.fail)

      override def getUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(
              userOtpRows.get(otpID).filter(_.userID == userID).filter(_.otpType == otpType)
            )
          )(ZIO.fail)

      override def getUserOtpByOtpID(otpID: OtpID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpByOtpIDCounterRef.incrementAndGet *>
          serviceErrorOpt.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(
              userOtpRows.get(otpID).filter(_.otpType == otpType)
            )
          )(ZIO.fail)

      override def getUserOtpByUserID(userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpByUserIDCounterRef.incrementAndGet *>
          serviceErrorOpt.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(
              userOtpRows.values.find(row => row.userID == userID && row.otpType == otpType)
            )
          )(ZIO.fail)

      override def updateUserOtp(
          otpID: OtpID,
          userID: UserID,
          otpType: OtpType,
          expiresAtUpdate: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        updateUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.succeed(
              userOtpRows(otpID)
            )
          )(ZIO.fail)

      override def deleteUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Unit] =
        deleteUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.unit
          )(ZIO.fail)

    }
  )
}
