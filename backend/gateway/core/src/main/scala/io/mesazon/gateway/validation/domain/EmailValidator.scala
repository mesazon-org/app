package io.mesazon.gateway.validation.domain

import cats.data.{NonEmptyChain, ValidatedNec}
import cats.syntax.all.*
import com.sanctionco.jmail.JMail
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.{UIO, ZIO, ZLayer}

final class EmailValidator {

  def validate[E](
      emailRaw: String,
      emailConstructor: String => Either[String, E],
  ): UIO[ValidatedNec[InvalidFieldError, E]] =
    (for {
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
        .fromEither(emailConstructor(emailRawFormatted))
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

  def validateOptional[E](
      emailRawOpt: Option[String],
      emailConstructor: String => Either[String, E],
  ): UIO[ValidatedNec[InvalidFieldError, Option[E]]] =
    emailRawOpt match {
      case None      => ZIO.succeed(none[E].validNec)
      case Some(raw) => validate(raw, emailConstructor).map(_.map(_.some))
    }
}

object EmailValidator {

  val live = ZLayer.derive[EmailValidator]
}
