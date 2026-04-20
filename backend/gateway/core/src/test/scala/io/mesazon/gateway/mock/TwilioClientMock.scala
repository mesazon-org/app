package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.TwilioClient
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait TwilioClientMock extends ZIOTestOps, should.Matchers {
  private val sendOtpSmsCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkTwilioClient(expectedSendOtpCalls: Int = 0): Assertion =
    sendOtpSmsCounterRef.get.zioValue shouldBe expectedSendOtpCalls

  def twilioClientMockLive(maybeServiceError: Option[ServiceError] = None): ULayer[TwilioClient] =
    ZLayer.succeed(
      new TwilioClient {

        override def sendOtpSms(to: PhoneNumberE164, otp: Otp): IO[ServiceError, Unit] =
          sendOtpSmsCounterRef.incrementAndGet *> maybeServiceError.fold(ZIO.unit)(ZIO.fail)
      }
    )
}
