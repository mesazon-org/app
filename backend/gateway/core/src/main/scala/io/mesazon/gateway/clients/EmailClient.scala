package io.mesazon.gateway.clients

import html.{EmailVerification, Welcome}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.EmailConfig
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder
import zio.*

import scala.util.chaining.scalaUtilChainingOps

trait EmailClient {
  def sendEmailVerificationEmail(
      email: Email,
      otp: OTP,
  ): IO[ServiceError.InternalServerError.UnexpectedError, Unit]

  def sendWelcomeEmail(
      email: Email,
      fullName: FullName,
  ): IO[ServiceError.InternalServerError.UnexpectedError, Unit]
}

object EmailClient {

  private final class EmailClientImpl(emailConfig: EmailConfig) extends EmailClient {

    private val mailer =
      MailerBuilder
        .withSMTPServer(emailConfig.host, emailConfig.port, emailConfig.senderEmail, emailConfig.senderPassword)
        .pipe(builder =>
          if (emailConfig.enableTls) builder.withTransportStrategy(TransportStrategy.SMTP_TLS)
          else builder
        )
        .buildMailer()

    override def sendEmailVerificationEmail(
        email: Email,
        otp: OTP,
    ): IO[ServiceError.InternalServerError.UnexpectedError, Unit] =
      ZIO
        .fromCompletableFuture(
          mailer.sendMail(
            EmailBuilder
              .startingBlank()
              .from(emailConfig.senderEmail)
              .to(email.value)
              .withSubject("Mesazon email verification")
              .withHTMLText(
                EmailVerification
                  .render(emailConfig.redirectUri.addPath(otp).toStringSafe())
                  .toString()
              )
              .buildEmail()
          )
        )
        .unit
        .mapError(error =>
          ServiceError.InternalServerError.UnexpectedError("Failed to sendEmailVerificationEmail", Some(error))
        )

    override def sendWelcomeEmail(
        email: Email,
        fullName: FullName,
    ): IO[ServiceError.InternalServerError.UnexpectedError, Unit] =
      ZIO
        .fromCompletableFuture(
          mailer.sendMail(
            EmailBuilder
              .startingBlank()
              .from(emailConfig.senderEmail)
              .to(email.value)
              .withSubject("Welcome to Mesazon!")
              .withHTMLText(
                Welcome
                  .render(fullName.value)
                  .toString()
              )
              .buildEmail()
          )
        )
        .unit
        .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to sendWelcomeEmail", Some(error)))
  }

  def observed(emailClient: EmailClient): EmailClient = emailClient

  val live = ZLayer.derive[EmailClientImpl] >>> ZLayer.fromFunction(observed)
}
