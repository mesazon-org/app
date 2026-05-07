package io.mesazon.gateway.mock

import io.mesazon.clock.TimeProvider
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.time.temporal.ChronoUnit
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

  def timeProviderMockLive(javaClock: JavaClock): ULayer[TimeProvider] =
    ZLayer.succeed(
      new TimeProvider {

        override def clock: UIO[JavaClock] =
          clockCounterRef.incrementAndGet *> ZIO.succeed(javaClock)

        override def instantNow: UIO[Instant] =
          instantNowCounterRef.incrementAndGet *> ZIO.succeed(Instant.now(javaClock).truncatedTo(ChronoUnit.MILLIS))
      }
    )
}
