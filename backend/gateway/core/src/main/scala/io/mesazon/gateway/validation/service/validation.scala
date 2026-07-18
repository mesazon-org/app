package io.mesazon.gateway.validation.service

import cats.Show
import cats.data.*
import cats.syntax.all.*
import io.mesazon.domain.gateway.ServiceError
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

import scala.util.chaining.scalaUtilChainingOps

private[validation] def toValidatedRequestIO[B](
    validatedFields: UIO[ValidatedNec[InvalidFieldError, B]]
): IO[ServiceError.BadRequestError.ValidationError, B] =
  validatedFields
    .flatMap(validated => ZIO.fromEither(validated.toEither))
    .mapError(_.toNonEmptyList.toList)
    .mapError(ServiceError.BadRequestError.ValidationError.apply)

private[validation] def validateAll[A, B](
    items: List[A]
)(validate: A => UIO[ValidatedNec[InvalidFieldError, B]]): UIO[ValidatedNec[InvalidFieldError, List[B]]] =
  ZIO
    .foreach(items.zipWithIndex) { case (item, index) =>
      validate(item).map(_.leftMap(_.map(_.copy(index = index))))
    }
    .map(_.sequence)

private[validation] def validateSingleDefault[A](
    fieldName: String,
    items: List[A],
)(isDefault: A => Boolean): ValidatedNec[InvalidFieldError, List[A]] =
  if (items.nonEmpty && items.count(isDefault) != 1)
    InvalidFieldError(fieldName, "Exactly one entry must be marked as default", Seq.empty).invalidNec
  else items.validNec

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
