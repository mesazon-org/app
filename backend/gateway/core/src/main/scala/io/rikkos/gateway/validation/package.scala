package io.rikkos.gateway

import cats.Show
import cats.data.*
import cats.syntax.all.*
import io.mesazon.domain.gateway.ServiceError
import zio.ZIO

import scala.util.chaining.scalaUtilChainingOps

import ServiceError.BadRequestError.InvalidFieldError

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
    values.partitionMap(constructor).pipe { case (errors, validated) =>
      validated.headOption
        .toRight(
          errors.headOption
            .map(errorMessage => InvalidFieldError(fieldName, errorMessage, values.map(_.show)))
            .getOrElse(InvalidFieldError(fieldName, "Unexpected error", values.map(_.show)))
        )
        .toValidatedNec
    }

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
    .partitionMap(_.traverse(constructor))
    .pipe { case (errors, validated) =>
      validated.headOption
        .toRight(
          errors.headOption
            .map(errorMessage => InvalidFieldError(fieldName, errorMessage, values.map(_.show)))
            .getOrElse(InvalidFieldError(fieldName, "Unexpected error", values.map(_.show)))
        )
    }
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
