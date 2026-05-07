package io.mesazon.gateway.mock

import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.util.concurrent.atomic.AtomicInteger

trait IDGeneratorMock extends ZIOTestOps, should.Matchers {
  private val generateCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkIDGenerator(
      expectedGenerateCalls: Int = 0
  ): Assertion =
    generateCounterRef.get.zioValue shouldBe expectedGenerateCalls

  def idGeneratorMockLive(id: Option[String] = None): ULayer[IDGenerator] =
    ZLayer.succeed(
      new IDGenerator {
        val atomicInt = new AtomicInteger(0)

        override def generate: UIO[String] =
          generateCounterRef.incrementAndGet *> ZIO.succeed(id.getOrElse(s"id-${atomicInt.incrementAndGet()}"))
      }
    )
}
