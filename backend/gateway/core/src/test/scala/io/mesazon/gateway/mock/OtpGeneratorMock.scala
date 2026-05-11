package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.Otp
import io.mesazon.gateway.utils.OtpGenerator
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait OtpGeneratorMock extends ZIOTestOps, should.Matchers {
  private val generateCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkOtpGenerator(
      expectedGenerateCalls: Int = 0
  ): Assertion =
    generateCounterRef.get.zioValue shouldBe expectedGenerateCalls

  def otpGeneratorMockLive(generateOtpOutput: Option[Otp] = None): ULayer[OtpGenerator] =
    ZLayer.succeed(
      new OtpGenerator {
        override def generateOtp: UIO[Otp] =
          generateCounterRef.incrementAndGet *> ZIO.succeed(generateOtpOutput.get)
      }
    )
}
