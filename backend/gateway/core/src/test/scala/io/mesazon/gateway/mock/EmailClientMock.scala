package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait EmailClientMock extends ZIOTestOps, should.Matchers {
  private val sendEmailVerificationEmailCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val sendWelcomeEmailCounterRef: Ref[Int]           = Ref.make(0).zioValue

  def checkEmailClient(
      expectedSendEmailVerificationEmailCalls: Int = 0,
      expectedSendWelcomeEmailCalls: Int = 0,
  ): Assertion = {
    sendEmailVerificationEmailCounterRef.get.zioValue shouldBe expectedSendEmailVerificationEmailCalls
    sendWelcomeEmailCounterRef.get.zioValue shouldBe expectedSendWelcomeEmailCalls
  }

  def emailClientMockLive(
      maybeServiceError: Option[ServiceError] = None
  ): ULayer[EmailClient] =
    ZLayer.succeed(
      new EmailClient {
        override def sendEmailVerificationEmail(
            email: Email,
            otp: Otp,
        ): IO[ServiceError, Unit] =
          sendEmailVerificationEmailCounterRef.incrementAndGet *> maybeServiceError.fold(ZIO.unit)(ZIO.fail(_).orDie)

        override def sendWelcomeEmail(email: Email): IO[ServiceError, Unit] =
          sendWelcomeEmailCounterRef.incrementAndGet *> maybeServiceError.fold(ZIO.unit)(ZIO.fail(_).orDie)
      }
    )
}
