package io.rikkos.gateway.validation

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.BadRequestError
import io.rikkos.gateway.smithy
import zio.*

trait RequestValidator[A, B] {
  def validate(request: A): ValidatedNec[String, B]
}

object RequestValidator {

  // TODO: include fieldName in error messages make logs more readable and add more context about where did this came from.
  given RequestValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails] =
    (request: smithy.OnboardUserDetailsRequest) =>
      (
        FirstName.either(request.firstName).toValidatedNec,
        LastName.either(request.lastName).toValidatedNec,
        Organization.either(request.organization).toValidatedNec,
      ).mapN(OnboardUserDetails.apply)

  extension [A](request: A) {
    def validate[B](using
        validator: RequestValidator[A, B],
        trace: Trace,
    ): IO[BadRequestError.RequestValidationError, B] =
      validator
        .validate(request)
        .fold(
          errors => ZIO.fail(BadRequestError.RequestValidationError(errors.toNonEmptyList.toList)),
          ZIO.succeed(_),
        )
  }
}
