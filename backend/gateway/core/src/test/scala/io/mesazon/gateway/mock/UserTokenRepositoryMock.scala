package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserTokenRepository
import io.mesazon.gateway.repository.domain.UserTokenRow
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait UserTokenRepositoryMock extends ZIOTestOps, should.Matchers {
  private val upsertUserTokenCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val getUserTokenCounterRef: Ref[Int]        = Ref.make(0).zioValue
  private val deleteUserTokenCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val deleteAllUserTokensCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkUserTokenRepository(
      expectedUpsertUserTokenCalls: Int = 0,
      expectedGetUserTokenCalls: Int = 0,
      expectedDeleteUserTokenCalls: Int = 0,
      expectedDeleteAllUserTokensCalls: Int = 0,
  ): Assertion = {
    upsertUserTokenCounterRef.get.zioValue shouldBe expectedUpsertUserTokenCalls
    getUserTokenCounterRef.get.zioValue shouldBe expectedGetUserTokenCalls
    deleteUserTokenCounterRef.get.zioValue shouldBe expectedDeleteUserTokenCalls
    deleteAllUserTokensCounterRef.get.zioValue shouldBe expectedDeleteAllUserTokensCalls
  }

  def userTokenRepositoryMockLive(
      userTokenRows: Map[TokenID, UserTokenRow] = Map.empty,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ): ULayer[UserTokenRepository] = ZLayer.succeed(
    new UserTokenRepository {

      override def upsertUserToken(
          tokenID: TokenID,
          userID: UserID,
          tokenType: TokenType,
          expiresAt: ExpiresAt,
          tokenIDOptOld: Option[TokenID] = None,
      ): IO[ServiceError, Unit] = upsertUserTokenCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
        maybeServiceError.fold(
          ZIO.unit
        )(ZIO.fail)
      )(ZIO.fail(_).orDie)

      override def getUserToken(
          tokenID: TokenID,
          userID: UserID,
          tokenType: TokenType,
      ): IO[ServiceError, Option[UserTokenRow]] =
        getUserTokenCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          maybeServiceError.fold[IO[ServiceError, Option[UserTokenRow]]](
            ZIO.succeed(
              userTokenRows.get(tokenID).filter(row => row.userID == userID && row.tokenType == tokenType)
            )
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)

      override def deleteUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): IO[ServiceError, Unit] =
        deleteUserTokenCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          maybeServiceError.fold(
            ZIO.unit
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)

      override def deleteAllUserTokens(userID: UserID): IO[ServiceError, Unit] =
        deleteAllUserTokensCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          maybeServiceError.fold(
            ZIO.unit
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)
    }
  )
}
