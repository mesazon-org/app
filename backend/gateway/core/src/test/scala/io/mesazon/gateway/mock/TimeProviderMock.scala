package io.mesazon.gateway.mock

import io.mesazon.clock.TimeProvider
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.time.{Clock as JavaClock, Instant}

trait TimeProviderMock extends ZIOTestOps, should.Matchers {
  private val clockCounterRef: Ref[Int]      = Ref.make(0).zioValue
  private val instantNowCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkTimeProvider(
      expectedClockCalls: Int = 0,
      expectedInstantNowCalls: Int = 0,
  ): Assertion = {
    clockCounterRef.get.zioValue shouldBe expectedClockCalls
    instantNowCounterRef.get.zioValue shouldBe expectedInstantNowCalls
  }

  def timeProviderMockLive(
      instantNowOutput: Option[Instant] = None,
      clockOutput: Option[JavaClock] = None,
  ): ULayer[TimeProvider] =
    ZLayer.succeed(
      new TimeProvider {

        override def clock: UIO[JavaClock] =
          clockCounterRef.incrementAndGet *> ZIO.succeed(clockOutput.get)

        override def instantNow: UIO[Instant] =
          instantNowCounterRef.incrementAndGet *> ZIO.succeed(instantNowOutput.get)
      }
    )
}
