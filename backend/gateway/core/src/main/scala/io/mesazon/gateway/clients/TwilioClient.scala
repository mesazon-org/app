package io.mesazon.gateway.clients

import com.twilio.Twilio
import com.twilio.`type`.PhoneNumber as TwilioPhoneNumber
import com.twilio.http.TwilioRestClient
import com.twilio.rest.api.v2010.account.Message as TwilioMessage
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.TwilioClientConfig
import zio.*

trait TwilioClient {
  def sendOtpSms(to: PhoneNumberE164, otp: Otp): IO[ServiceError, Unit]
}

object TwilioClient {

  private final class TwilioClientImpl(
      twilioClientConfig: TwilioClientConfig,
      twilioRestClient: TwilioRestClient,
  ) extends TwilioClient {

    override def sendOtpSms(to: PhoneNumberE164, otp: Otp): IO[ServiceError, Unit] =
      ZIO
        .fromCompletableFuture(
          TwilioMessage
            .creator(
              new TwilioPhoneNumber(to.value),
              new TwilioPhoneNumber(twilioClientConfig.phoneNumber),
              "Your OTP code is: " + otp.value,
            )
            .createAsync(twilioRestClient)
        )
        .unit
        .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to send OTP SMS", Some(error)))
  }

  private def make(twilioClientConfig: TwilioClientConfig): UIO[TwilioRestClient] =
    ZIO.attemptBlocking(Twilio.init(twilioClientConfig.accountSid, twilioClientConfig.authToken)).orDie *> ZIO.succeed(
      Twilio.getRestClient
    )

  private def observed(twilioRestClient: TwilioRestClient): TwilioRestClient = twilioRestClient

  val live = ZLayer.fromFunction(make) >>> ZLayer.derive[TwilioClientImpl] >>> ZLayer.fromFunction(observed)
}
