package io.rikkos.gateway.validation

import cats.syntax.all.*
import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.gateway.smithy
import io.rikkos.gateway.validation.PhoneNumberValidator.PhoneNumberParams
import zio.*
import zio.interop.catz.*

object UserManagementValidators {

  private def onboardUserDetailsRequestValidator(
      phoneNumberValidator: DomainValidator[PhoneNumberParams, PhoneNumber]
  ): DomainValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails] = { request =>
    phoneNumberValidator
      .validate(request.phoneRegion, request.phoneNationalNumber)
      .map(validatedPhoneNumber =>
        (
          validateRequiredField(firstNameFieldName, request.firstName, FirstName.either),
          validateRequiredField(lastNameFieldName, request.lastName, LastName.either),
          validatedPhoneNumber,
          validateRequiredField(addressLine1FieldName, request.addressLine1, AddressLine1.either),
          validateOptionalField(addressLine2FieldName, request.addressLine2, AddressLine2.either),
          validateRequiredField(cityFieldName, request.city, City.either),
          validateRequiredField(postalCodeFieldName, request.postalCode, PostalCode.either),
          validateRequiredField(companyFieldName, request.company, Company.either),
        ).mapN(OnboardUserDetails.apply)
      )
  }

  private def updateUserDetailsRequestValidator(
      phoneNumberValidator: DomainValidator[PhoneNumberParams, PhoneNumber]
  ): DomainValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails] = { request =>
    for {
      maybeValidatedPhoneNumber <-
        (request.phoneRegion zip request.phoneNationalNumber).traverse(phoneNumberValidator.validate)
      validatedPhoneNumber = maybeValidatedPhoneNumber
        .fold(Option.empty[PhoneNumber].validNec[InvalidFieldError])(_.map(Some(_)))
      validatedRequest = (
        validateOptionalField(firstNameFieldName, request.firstName, FirstName.either),
        validateOptionalField(lastNameFieldName, request.lastName, LastName.either),
        validatedPhoneNumber,
        validateOptionalField(addressLine1FieldName, request.addressLine1, AddressLine1.either),
        validateOptionalField(addressLine2FieldName, request.addressLine2, AddressLine2.either),
        validateOptionalField(cityFieldName, request.city, City.either),
        validateOptionalField(postalCodeFieldName, request.postalCode, PostalCode.either),
        validateOptionalField(companyFieldName, request.company, Company.either),
      ).mapN(UpdateUserDetails.apply)
    } yield validatedRequest
  }

  val onboardUserDetailsRequestValidatorLive =
    ZLayer.fromFunction(onboardUserDetailsRequestValidator andThen toServiceValidator)

  val updateUserDetailsRequestValidatorLive =
    ZLayer.fromFunction(updateUserDetailsRequestValidator andThen toServiceValidator)
}
