package io.rikkos.gateway

import cats.data.*
import cats.syntax.all.*
import io.rikkos.domain.ServiceError
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import zio.ZIO

package object validation {

  private[validation] inline val phoneRegionFieldName         = "phoneRegion"
  private[validation] inline val phoneNationalNumberFieldName = "phoneNationalNumber"
  private[validation] inline val firstNameFieldName           = "firstName"
  private[validation] inline val lastNameFieldName            = "lastName"
  private[validation] inline val addressLine1FieldName        = "addressLine1"
  private[validation] inline val addressLine2FieldName        = "addressLine2"
  private[validation] inline val cityFieldName                = "city"
  private[validation] inline val postalCodeFieldName          = "postalCode"
  private[validation] inline val companyFieldName             = "company"

  private[validation] def validateRequiredField[A, T](
      fieldName: String,
      value: A,
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, T] =
    constructor(value).left.map(errorMessage => (fieldName, errorMessage)).toValidatedNec

  private[validation] def validateOptionalField[A, T](
      fieldName: String,
      value: Option[A],
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, Option[T]] =
    value.traverse(constructor).left.map(errorMessage => (fieldName, errorMessage)).toValidatedNec

  private[validation] def toServiceValidator[A, B](
      domainValidator: DomainValidator[A, B]
  ): ServiceValidator[A, B] = rawData =>
    domainValidator
      .validate(rawData)
      .flatMap(validated => ZIO.fromEither(validated.toEither))
      .mapError(_.toNonEmptyList.toList)
      .mapError(ServiceError.BadRequestError.FormValidationError.apply)

}
