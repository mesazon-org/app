package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.matchers.should
import zio.*

trait JwtServiceMock extends ZIOTestOps, should.Matchers {
  private val generateAccessTokenCounterRef: zio.Ref[Int]        = zio.Ref.make(0).zioValue
  private val generateRefreshTokenCounterRef: zio.Ref[Int]       = zio.Ref.make(0).zioValue
  private val generateResetPasswordTokenCounterRef: zio.Ref[Int] = zio.Ref.make(0).zioValue
  private val verifyAccessTokenCounterRef: zio.Ref[Int]          = zio.Ref.make(0).zioValue
  private val verifyRefreshTokenCounterRef: zio.Ref[Int]         = zio.Ref.make(0).zioValue
  private val verifyResetPasswordTokenCounterRef: zio.Ref[Int]   = zio.Ref.make(0).zioValue

  def checkJwtService(
      expectedGenerateAccessTokenCalls: Int = 0,
      expectedGenerateRefreshTokenCalls: Int = 0,
      expectedGenerateResetPasswordTokenCalls: Int = 0,
      expectedVerifyAccessTokenCalls: Int = 0,
      expectedVerifyRefreshTokenCalls: Int = 0,
      expectedVerifyResetPasswordTokenCalls: Int = 0,
  ): Unit = {
    generateAccessTokenCounterRef.get.zioValue shouldBe expectedGenerateAccessTokenCalls
    generateRefreshTokenCounterRef.get.zioValue shouldBe expectedGenerateRefreshTokenCalls
    generateResetPasswordTokenCounterRef.get.zioValue shouldBe expectedGenerateResetPasswordTokenCalls
    verifyAccessTokenCounterRef.get.zioValue shouldBe expectedVerifyAccessTokenCalls
    verifyRefreshTokenCounterRef.get.zioValue shouldBe expectedVerifyRefreshTokenCalls
    verifyResetPasswordTokenCounterRef.get.zioValue shouldBe expectedVerifyResetPasswordTokenCalls
  }

  def jwtServiceMockLive(
      generateAccessTokenOutput: Option[AccessJwt] = None,
      generateRefreshTokenOutput: Option[RefreshJwt] = None,
      generateResetPasswordTokenOutput: Option[ResetPasswordJwt] = None,
      verifyAccessTokenOutput: Option[AuthedUserAccess] = None,
      verifyRefreshTokenOutput: Option[AuthedUserRefresh] = None,
      verifyResetPasswordTokenOutput: Option[AuthedUserResetPassword] = None,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ) = ZLayer.succeed(
    new JwtService {
      override def generateAccessToken(userID: UserID): IO[ServiceError, AccessJwt] =
        generateAccessTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(generateAccessTokenOutput.get)
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def generateRefreshToken(userID: UserID): IO[ServiceError, RefreshJwt] =
        generateRefreshTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(generateRefreshTokenOutput.get)
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def generateResetPasswordToken(userID: UserID): IO[ServiceError, ResetPasswordJwt] =
        generateResetPasswordTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(generateResetPasswordTokenOutput.get)
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyAccessToken(accessToken: AccessToken): IO[ServiceError, AuthedUserAccess] =
        verifyAccessTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(verifyAccessTokenOutput.get)
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyRefreshToken(refreshToken: RefreshToken): IO[ServiceError, AuthedUserRefresh] =
        verifyRefreshTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(verifyRefreshTokenOutput.get)
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyResetPasswordToken(
          resetPasswordToken: ResetPasswordToken
      ): IO[ServiceError, AuthedUserResetPassword] =
        verifyResetPasswordTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(verifyResetPasswordTokenOutput.get)
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)
    }
  )
}
