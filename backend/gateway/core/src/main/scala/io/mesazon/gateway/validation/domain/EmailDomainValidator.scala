package io.mesazon.gateway.validation.domain

import cats.data.{NonEmptyChain, ValidatedNec}
import cats.syntax.all.*
import com.sanctionco.jmail.JMail
import io.mesazon.domain.gateway.Email
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

final class EmailDomainValidator extends DomainValidator[String, Email] {

  override def validate(emailRaw: String): UIO[ValidatedNec[InvalidFieldError, Email]] = (for {
    emailRawFormatted = emailRaw.trim.toLowerCase
    _ <- ZIO
      .attempt(JMail.enforceValid(emailRawFormatted))
      .mapError(error =>
        NonEmptyChain(
          InvalidFieldError(
            "email",
            s"Invalid email format: [$emailRaw], error: [${error.getMessage}]",
            emailRaw,
          )
        )
      )
    email <- ZIO
      .fromEither(Email.either(emailRawFormatted))
      .mapError(errorMessage =>
        NonEmptyChain(
          InvalidFieldError(
            "email",
            s"Couldn't construct Email: [$emailRaw], error: [${errorMessage}]",
            emailRaw,
          )
        )
      )
  } yield email).fold(_.invalid, _.valid)
}

object EmailDomainValidator {

  val live = ZLayer.derive[EmailDomainValidator]
}
