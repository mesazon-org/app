package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.JwtService
import io.mesazon.gateway.auth.JwtService.{AccessJwt, AuthedUserAccess, AuthedUserRefresh, RefreshJwt}
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.matchers.should
import zio.*

import java.time.Instant

trait JwtServiceMock extends ZIOTestOps, should.Matchers {
  private val generateAccessTokenCounterRef: zio.Ref[Int]  = zio.Ref.make(0).zioValue
  private val generateRefreshTokenCounterRef: zio.Ref[Int] = zio.Ref.make(0).zioValue
  private val verifyAccessTokenCounterRef: zio.Ref[Int]    = zio.Ref.make(0).zioValue
  private val verifyRefreshTokenCounterRef: zio.Ref[Int]   = zio.Ref.make(0).zioValue

  def checkJwtService(
      expectedGenerateAccessTokenCalls: Int = 0,
      expectedGenerateRefreshTokenCalls: Int = 0,
      expectedVerifyAccessTokenCalls: Int = 0,
      expectedVerifyRefreshTokenCalls: Int = 0,
  ): Unit = {
    generateAccessTokenCounterRef.get.zioValue shouldBe expectedGenerateAccessTokenCalls
    generateRefreshTokenCounterRef.get.zioValue shouldBe expectedGenerateRefreshTokenCalls
    verifyAccessTokenCounterRef.get.zioValue shouldBe expectedVerifyAccessTokenCalls
    verifyRefreshTokenCounterRef.get.zioValue shouldBe expectedVerifyRefreshTokenCalls
  }

  def jwtServiceMockLive(
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ) = ZLayer.succeed(
    new JwtService {
      override def generateAccessToken(userID: UserID, onboardStage: OnboardStage): IO[ServiceError, AccessJwt] =
        generateAccessTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed((Jwt.assume("mock-access-jwt"), 1.minute))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def generateRefreshToken(userID: UserID, onboardStage: OnboardStage): IO[ServiceError, RefreshJwt] =
        generateRefreshTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(
                (TokenID.assume("mock-refresh-jwt-id"), Jwt.assume("mock-refresh-jwt"), ExpiresAt(Instant.now))
              )
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyAccessToken(jwt: Jwt): IO[ServiceError, AuthedUserAccess] =
        verifyAccessTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed((UserID.assume("test"), OnboardStage.EmailVerification))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyRefreshToken(jwt: Jwt): IO[ServiceError, AuthedUserRefresh] =
        verifyRefreshTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed((TokenID.assume("mock-refresh-jwt-id"), UserID.assume("test")))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)
    }
  )
}
