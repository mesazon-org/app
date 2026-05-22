package io.mesazon.gateway.validation.domain

import cats.data.*
import cats.syntax.all.*
import com.sanctionco.jmail.JMail
import io.github.iltotore.iron.*
import io.mesazon.domain.EmailPredicate
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

final class EmailDomainValidator extends DomainValidator[String, String :| EmailPredicate] {

  override def validate(emailRaw: String): UIO[ValidatedNec[InvalidFieldError, String :| EmailPredicate]] = (for {
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
      .fromEither(emailRawFormatted.refineEither[EmailPredicate])
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
