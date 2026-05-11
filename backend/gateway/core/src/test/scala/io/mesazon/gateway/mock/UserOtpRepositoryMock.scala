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
      upsertUserOtpOutput: Option[UserOtpRow] = None,
      getUserOtpOutput: Option[UserOtpRow] = None,
      getUserOtpByOtpIDOutput: Option[UserOtpRow] = None,
      getUserOtpByUserIDOutput: Option[UserOtpRow] = None,
      updateUserOtpOutput: Option[UserOtpRow] = None,
      serviceErrorOpt: Option[ServiceError] = None,
  ): ULayer[UserOtpRepository] = ZLayer.succeed(
    new UserOtpRepository {
      override def upsertUserOtp(
          userID: UserID,
          otpType: OtpType,
          otp: Otp,
          expiresAt: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        upsertUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.succeed(upsertUserOtpOutput.get)
          )(ZIO.fail)

      override def getUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(getUserOtpOutput)
          )(ZIO.fail)

      override def getUserOtpByOtpID(otpID: OtpID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpByOtpIDCounterRef.incrementAndGet *>
          serviceErrorOpt.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(getUserOtpByOtpIDOutput)
          )(ZIO.fail)

      override def getUserOtpByUserID(userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
        getUserOtpByUserIDCounterRef.incrementAndGet *>
          serviceErrorOpt.fold[IO[ServiceError, Option[UserOtpRow]]](
            ZIO.succeed(getUserOtpByUserIDOutput)
          )(ZIO.fail)

      override def updateUserOtp(
          otpID: OtpID,
          userID: UserID,
          otpType: OtpType,
          expiresAtUpdate: ExpiresAt,
      ): IO[ServiceError, UserOtpRow] =
        updateUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.succeed(updateUserOtpOutput.get)
          )(ZIO.fail)

      override def deleteUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): IO[ServiceError, Unit] =
        deleteUserOtpCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.unit
          )(ZIO.fail)

    }
  )
}
