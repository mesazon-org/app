package io.rikkos.gateway

import cats.Show
import cats.data.*
import cats.syntax.all.*
import io.rikkos.domain.gateway.ServiceError
import io.rikkos.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.ZIO

package object validation {

  private[validation] def validateRequiredField[A: Show, T](
      fieldName: String,
      value: A,
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, T] =
    constructor(value).left
      .map(errorMessage => InvalidFieldError(fieldName, errorMessage, Seq(value.show)))
      .toValidatedNec

  private[validation] def validateRequiredFields[A: Show, T](
      fieldName: String,
      values: Seq[A],
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, T] =
    values
      .traverse(constructor)
      .left
      .map(errorMessage => InvalidFieldError(fieldName, errorMessage, values.map(_.show)))
      .flatMap(_.headOption.toRight(InvalidFieldError(fieldName, "Unexpected error", values.map(_.show))))
      .toValidatedNec

  private[validation] def validateOptionalField[A: Show, T](
      fieldName: String,
      value: Option[A],
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, Option[T]] =
    value
      .traverse(constructor)
      .left
      .map(errorMessage => InvalidFieldError(fieldName, errorMessage, value.map(_.show).toList))
      .toValidatedNec

  private[validation] def validateOptionalFields[A: Show, T](
      fieldName: String,
      values: Seq[Option[A]],
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, Option[T]] = values
    .traverse(_.traverse(constructor))
    .left
    .map(errorMessage => InvalidFieldError(fieldName, errorMessage, values.flatMap(_.map(_.show))))
    .flatMap(_.headOption.toRight(InvalidFieldError(fieldName, "Unexpected error", values.flatMap(_.map(_.show)))))
    .toValidatedNec

  private[validation] def toServiceValidator[A, B](
      domainValidator: DomainValidator[A, B]
  ): ServiceValidator[A, B] = rawData =>
    domainValidator
      .validate(rawData)
      .flatMap(validated => ZIO.fromEither(validated.toEither))
      .mapError(_.toNonEmptyList.toList)
      .mapError(ServiceError.BadRequestError.FormValidationError.apply)

}
