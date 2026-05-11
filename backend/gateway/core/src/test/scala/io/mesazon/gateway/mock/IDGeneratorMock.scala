package io.mesazon.gateway.mock

import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.util.UUID

trait IDGeneratorMock extends ZIOTestOps, should.Matchers {
  private val generateCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkIDGenerator(
      expectedGenerateCalls: Int = 0
  ): Assertion =
    generateCounterRef.get.zioValue shouldBe expectedGenerateCalls

  def idGeneratorMockLive(generateIDOutput: Option[UUID] = None): ULayer[IDGenerator] =
    ZLayer.succeed(
      new IDGenerator {
        override def generateID: UIO[UUID] =
          generateCounterRef.incrementAndGet *> ZIO.succeed(generateIDOutput.getOrElse(UUID.randomUUID()))
      }
    )
}
