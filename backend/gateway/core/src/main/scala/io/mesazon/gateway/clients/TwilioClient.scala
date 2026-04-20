package io.mesazon.gateway.clients

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.TwilioClientConfig
import sttp.client4.*
import zio.*

trait TwilioClient {
  def sendOtpSms(phoneNumberE16To: PhoneNumberE164, otp: Otp): IO[ServiceError, Unit]
}

object TwilioClient {

  private final class TwilioClientImpl(
      twilioClientConfig: TwilioClientConfig,
      sttpBackend: Backend[Task],
  ) extends TwilioClient {

    override def sendOtpSms(phoneNumberE16To: PhoneNumberE164, otp: Otp): IO[ServiceError, Unit] =
      basicRequest
        .post(
          twilioClientConfig.baseUri.addPath("2010-04-01", "Accounts", twilioClientConfig.accountSid, "Messages.json")
        )
        .auth
        .basic(twilioClientConfig.accountSid, twilioClientConfig.authToken)
        .body(
          Map(
            "To"   -> phoneNumberE16To.value,
            "From" -> twilioClientConfig.companyName,
            "Body" -> s"Your Mesazon verification code is: ${otp.value}. Valid for 5 minutes. Do not share this code.",
          )
        )
        .response(asStringAlways)
        .send(sttpBackend)
        .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to send OTP SMS", Some(error)))
        .flatMap { response =>
          if (response.code.isSuccess) {
            ZIO.unit
          } else {
            val responseBody = response.body.trim
            val message      =
              if (responseBody.nonEmpty)
                s"Failed to send OTP SMS: Twilio returned HTTP [${response.code.code}]: $responseBody"
              else
                s"Failed to send OTP SMS: Twilio returned HTTP [${response.code.code}]"

            ZIO.fail(ServiceError.InternalServerError.UnexpectedError(message, None))
          }
        }
  }

  private def observed(twilioClientImpl: TwilioClientImpl): TwilioClient = twilioClientImpl

  val live = ZLayer.derive[TwilioClientImpl] >>> ZLayer.fromFunction(observed)
}
