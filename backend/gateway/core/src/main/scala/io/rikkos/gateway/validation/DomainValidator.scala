package io.rikkos.gateway.validation

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.gateway.smithy
import zio.*

trait DomainValidator[A, B] {
  def validate(rawData: A): IO[ServiceError.BadRequestError, B]
}

object DomainValidator {

  private def validateRequiredField[A, T](
      fieldName: String,
      value: A,
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, T] =
    constructor(value).left.map(errorMessage => (fieldName, errorMessage)).toValidatedNec

  private def validateOptionalField[A, T](
      fieldName: String,
      value: Option[A],
      constructor: A => Either[String, T],
  ): ValidatedNec[InvalidFieldError, Option[T]] =
    value.traverse(constructor).left.map(errorMessage => (fieldName, errorMessage)).toValidatedNec

  private def onboardUserDetailsRequestValidator(
      request: smithy.OnboardUserDetailsRequest
  ): ValidatedNec[InvalidFieldError, OnboardUserDetails] =
    (
      validateRequiredField("firstName", request.firstName, FirstName.either),
      validateRequiredField("lastName", request.lastName, LastName.either),
      validateRequiredField("countryCode", request.countryCode, CountryCode.either),
      validateRequiredField("phoneNumber", request.phoneNumber, PhoneNumber.either),
      validateRequiredField("addressLine1", request.addressLine1, AddressLine1.either),
      validateOptionalField("addressLine1", request.addressLine2, AddressLine2.either),
      validateRequiredField("city", request.city, City.either),
      validateRequiredField("postalCode", request.postalCode, PostalCode.either),
      validateRequiredField("company", request.company, Company.either),
    ).mapN(OnboardUserDetails.apply)

  private def observed[A, B](
      validator: A => ValidatedNec[InvalidFieldError, B]
  ): DomainValidator[A, B] = rawData =>
    ZIO
      .fromEither(validator(rawData).toEither)
      .mapError(_.toNonEmptyList.toList)
      .mapError(ServiceError.BadRequestError.FormValidationError.apply)

  val liveOnboardUserDetailsRequestValidator =
    ZLayer.succeed(observed(onboardUserDetailsRequestValidator))
}
