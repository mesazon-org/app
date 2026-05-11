package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait UserActionAttemptRepositoryMock extends ZIOTestOps with should.Matchers {
  private val getAndIncreaseUserActionAttemptCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val deleteUserActionAttemptCounterRef: Ref[Int]         = Ref.make(0).zioValue

  def checkUserActionAttemptRepository(
      expectedGetAndIncreaseUserActionAttemptCalls: Int = 0,
      expectedDeleteUserActionAttemptCalls: Int = 0,
  ): Assertion = {
    getAndIncreaseUserActionAttemptCounterRef.get.zioValue shouldBe expectedGetAndIncreaseUserActionAttemptCalls
    deleteUserActionAttemptCounterRef.get.zioValue shouldBe expectedDeleteUserActionAttemptCalls
  }

  def userActionAttemptRepositoryMockLive(
      getAndIncreaseUserActionAttemptOutput: Option[UserActionAttemptRow] = None,
      serviceErrorOpt: Option[ServiceError] = None,
  ): ULayer[UserActionAttemptRepository] = ZLayer.succeed(
    new UserActionAttemptRepository {

      override def getAndIncreaseUserActionAttempt(
          userID: UserID,
          actionAttemptType: ActionAttemptType,
      ): IO[ServiceError, UserActionAttemptRow] =
        getAndIncreaseUserActionAttemptCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.succeed(getAndIncreaseUserActionAttemptOutput.get)
          )(ZIO.fail)

      override def deleteUserActionAttempt(
          userID: UserID,
          actionAttemptType: ActionAttemptType,
      ): IO[ServiceError, Unit] = deleteUserActionAttemptCounterRef.incrementAndGet *>
        serviceErrorOpt.fold(
          ZIO.unit
        )(ZIO.fail)
    }
  )
}
