package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.Otp
import io.mesazon.gateway.utils.OtpGenerator
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.util.concurrent.atomic.AtomicInteger

trait OtpGeneratorMock extends ZIOTestOps, should.Matchers {
  private val generateCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkOtpGenerator(
      expectedGenerateCalls: Int = 0
  ): Assertion =
    generateCounterRef.get.zioValue shouldBe expectedGenerateCalls

  def otpGeneratorMockLive(otp: Option[Otp] = None): ULayer[OtpGenerator] =
    ZLayer.succeed(
      new OtpGenerator {
        val atomicInt = new AtomicInteger(0)

        override def generate: UIO[Otp] =
          generateCounterRef.incrementAndGet *> ZIO.succeed(
            otp.getOrElse(Otp.applyUnsafe(s"OTPID${atomicInt.incrementAndGet()}"))
          )
      }
    )
}
