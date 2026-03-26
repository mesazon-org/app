package io.mesazon.gateway.validation

import cats.data.NonEmptyChain
import cats.syntax.all.*
import com.sanctionco.jmail.JMail
import io.mesazon.domain.gateway.Email
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

object EmailValidator {
  type EmailRaw = String

  private val emailValidator: DomainValidator[EmailRaw, Email] = { emailRaw =>
    (for {
      emailRawFormatted = emailRaw.trim.toLowerCase
      _ <- ZIO
        .attemptBlocking(JMail.enforceValid(emailRawFormatted))
        .mapError(error =>
          NonEmptyChain(
            InvalidFieldError(
              "email",
              s"Email raw [$emailRaw] formatted [$emailRawFormatted] is not valid format: ${error.getMessage}",
              emailRaw,
            )
          )
        )
      email <- ZIO
        .fromEither(Email.either(emailRawFormatted))
        .mapError(error =>
          NonEmptyChain(
            InvalidFieldError(
              "email",
              s"Email raw [$emailRaw] formatted [$emailRawFormatted] is not valid format: $error",
              emailRaw,
            )
          )
        )
    } yield email).fold(_.invalid, _.valid)
  }

  val emailValidatorLive = ZLayer.succeed(toServiceValidator(emailValidator))
}
