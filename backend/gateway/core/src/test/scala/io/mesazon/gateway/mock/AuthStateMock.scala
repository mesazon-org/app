package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.state.AuthState
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait AuthStateMock extends ZIOTestOps, should.Matchers {
  private val getCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val setCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkAuthState(
      expectedGetCalls: Int = 0,
      expectedSetCalls: Int = 0,
  ): Assertion = {
    getCounterRef.get.zioValue shouldBe expectedGetCalls
    setCounterRef.get.zioValue shouldBe expectedSetCalls
  }

  def authStateMockLive(
      authedUser: AuthedUser
  ): ULayer[AuthState] =
    ZLayer.succeed(
      new AuthState {
        override def get(): UIO[AuthedUser] =
          getCounterRef.incrementAndGet *> ZIO.succeed(authedUser)

        override def set(authedUser: AuthedUser): UIO[Unit] =
          setCounterRef.incrementAndGet *> ZIO.unit
      }
    )
}
