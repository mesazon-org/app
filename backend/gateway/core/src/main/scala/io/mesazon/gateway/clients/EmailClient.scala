package io.mesazon.gateway.clients

import html.*
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
      otp: Otp,
  ): IO[ServiceError, Unit]

  def sendWelcomeEmail(
      email: Email
  ): IO[ServiceError, Unit]

  def sendForgotPasswordEmail(
      email: Email,
      otp: Otp,
  ): IO[ServiceError, Unit]
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
        otp: Otp,
    ): IO[ServiceError, Unit] =
      ZIO
        .fromCompletableFuture(
          mailer.sendMail(
            EmailBuilder
              .startingBlank()
              .from(emailConfig.senderEmail)
              .to(email.value)
              .withSubject("Mesazon email verification")
              .withHTMLText(
                EmailVerificationHTML
                  .render(otp.value)
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
        email: Email
    ): IO[ServiceError, Unit] =
      ZIO
        .fromCompletableFuture(
          mailer.sendMail(
            EmailBuilder
              .startingBlank()
              .from(emailConfig.senderEmail)
              .to(email.value)
              .withSubject("Welcome to Mesazon!")
              .withHTMLText(
                WelcomeHTML
                  .render()
                  .toString()
              )
              .buildEmail()
          )
        )
        .unit
        .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to sendWelcomeEmail", Some(error)))

    override def sendForgotPasswordEmail(email: Email, otp: Otp): IO[ServiceError, Unit] =
      ZIO
        .fromCompletableFuture(
          mailer.sendMail(
            EmailBuilder
              .startingBlank()
              .from(emailConfig.senderEmail)
              .to(email.value)
              .withSubject("Mesazon password reset")
              .withHTMLText(
                ForgotPasswordHTML
                  .render(otp.value)
                  .toString()
              )
              .buildEmail()
          )
        )
        .unit
        .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to sendForgotEmail", Some(error)))
  }

  val live = ZLayer.derive[EmailClientImpl].project[EmailClient](identity)
}
