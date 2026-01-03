package io.rikkos.gateway

import cats.data.*
import cats.syntax.all.*
import io.rikkos.domain.ServiceError
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import zio.ZIO

package object validation {

  inline private[validation] val phoneRegionFieldName         = "phoneRegion"
  inline private[validation] val phoneNationalNumberFieldName = "phoneNationalNumber"
  inline private[validation] val firstNameFieldName           = "firstName"
  inline private[validation] val lastNameFieldName            = "lastName"
  inline private[validation] val addressLine1FieldName        = "addressLine1"
  inline private[validation] val addressLine2FieldName        = "addressLine2"
  inline private[validation] val cityFieldName                = "city"
  inline private[validation] val postalCodeFieldName          = "postalCode"
  inline private[validation] val companyFieldName             = "company"

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
