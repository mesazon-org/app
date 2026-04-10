//package io.mesazon.gateway.validation
//
//import cats.data.ValidatedNec
//import cats.syntax.all.*
//import io.mesazon.domain.gateway.*
//import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
//import io.mesazon.gateway.smithy
//import zio.*
//import zio.interop.catz.*
//
//import PhoneNumberValidator.PhoneNumberRegion
//
//object UserManagementValidators {
//
//  private def onboardUserDetailsRequestValidator(
//      phoneNumberValidator: DomainValidator[PhoneNumberRegion, PhoneNumberE164]
//  ): DomainValidator[smithy.OnboardUserDetailsRequest, OnboardUserDetails] = { request =>
//    phoneNumberValidator
//      .validate(request.phoneRegion, request.phoneNationalNumber)
//      .map(validatedPhoneNumber =>
//        (
//          validateRequiredField("firstName", request.firstName, FirstName.either),
//          validateRequiredField("lastName", request.lastName, LastName.either),
//          validatedPhoneNumber,
//          validateRequiredField("addressLine1", request.addressLine1, AddressLine1.either),
//          validateOptionalField("addressLine2", request.addressLine2, AddressLine2.either),
//          validateRequiredField("city", request.city, City.either),
//          validateRequiredField("postalCode", request.postalCode, PostalCode.either),
//          validateRequiredField("company", request.company, Company.either),
//        ).mapN(OnboardUserDetails.apply)
//      )
//  }
//
//  private def updateUserDetailsRequestValidator(
//      phoneNumberValidator: DomainValidator[PhoneNumberRegion, PhoneNumberE164]
//  ): DomainValidator[smithy.UpdateUserDetailsRequest, UpdateUserDetails] = { request =>
//    (for {
//      emptyUpdateUserDetailsRequest = smithy.UpdateUserDetailsRequest()
//      _ <-
//        if (emptyUpdateUserDetailsRequest == request)
//          ZIO.fail(InvalidFieldError("updateUserDetailsRequest", "request received all fields are empty", Seq.empty))
//        else ZIO.unit
//      maybeValidatedPhoneNumber <-
//        (request.phoneRegion zip request.phoneNationalNumber).traverse(phoneNumberValidator.validate)
//      validatedPhoneNumber = maybeValidatedPhoneNumber
//        .fold(Option.empty[PhoneNumberE164].validNec[InvalidFieldError])(_.map(Some(_)))
//      validatedRequest: ValidatedNec[InvalidFieldError, UpdateUserDetails] = (
//        validateOptionalField("firstName", request.firstName, FirstName.either),
//        validateOptionalField("lastName", request.lastName, LastName.either),
//        validatedPhoneNumber,
//        validateOptionalField("addressLine1", request.addressLine1, AddressLine1.either),
//        validateOptionalField("addressLine2", request.addressLine2, AddressLine2.either),
//        validateOptionalField("city", request.city, City.either),
//        validateOptionalField("postalCode", request.postalCode, PostalCode.either),
//        validateOptionalField("company", request.company, Company.either),
//      ).mapN(UpdateUserDetails.apply)
//    } yield validatedRequest).fold(_.invalidNec, identity)
//  }
//
//  val onboardUserDetailsRequestValidatorLive =
//    ZLayer.fromFunction(onboardUserDetailsRequestValidator andThen toServiceValidator)
//
//  val updateUserDetailsRequestValidatorLive =
//    ZLayer.fromFunction(updateUserDetailsRequestValidator andThen toServiceValidator)
//}
