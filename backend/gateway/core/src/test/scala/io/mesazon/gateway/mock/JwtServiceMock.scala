package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.matchers.should
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

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
      override def generateAccessToken(userID: UserID): IO[ServiceError, AccessJwt] =
        generateAccessTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed((AccessToken.assume("mock-access-jwt"), 1.minute))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def generateRefreshToken(userID: UserID): IO[ServiceError, RefreshJwt] =
        generateRefreshTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(
                (
                  TokenID.assume("mock-refresh-jwt-id"),
                  RefreshToken.assume("mock-refresh-jwt"),
                  ExpiresAt(Instant.now.truncatedTo(ChronoUnit.MILLIS)),
                )
              )
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyAccessToken(accessToken: AccessToken): IO[ServiceError, AuthedUserAccess] =
        verifyAccessTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(UserID.assume("test"))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

      override def verifyRefreshToken(refreshToken: RefreshToken): IO[ServiceError, AuthedUserRefresh] =
        verifyRefreshTokenCounterRef.incrementAndGet *>
          maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed((TokenID.assume("mock-refresh-jwt-id"), UserID.assume("test")))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)
    }
  )
}
