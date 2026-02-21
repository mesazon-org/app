//package io.rikkos.gateway.validation
//
//import io.mesazon.waha
//import io.rikkos.domain.ServiceError
//import io.rikkos.gateway.smithy.{WahaMessageText, WahaStartTyping, WahaStopTyping}
//import io.rikkos.gateway.{HttpErrorHandler, smithy}
//import zio.*
//
//object WahaValidator {
//
//  private def updateUserDetailsRequestValidator(
//      phoneNumberValidator: DomainValidator[PhoneNumberParams, PhoneNumber]
//  ): DomainValidator[smithy.WahaMessageText, UpdateUserDetails] = { request =>
//    (for {
//      emptyUpdateUserDetailsRequest = smithy.UpdateUserDetailsRequest()
//      _ <-
//        if (emptyUpdateUserDetailsRequest == request)
//          ZIO.fail(("updateUserDetailsRequest", "request received all fields are empty"))
//        else ZIO.unit
//      maybeValidatedPhoneNumber <-
//        (request.phoneRegion zip request.phoneNationalNumber).traverse(phoneNumberValidator.validate)
//      validatedPhoneNumber = maybeValidatedPhoneNumber
//        .fold(Option.empty[PhoneNumber].validNec[InvalidFieldError])(_.map(Some(_)))
//      validatedRequest = (
//        validateOptionalField(firstNameFieldName, request.firstName, FirstName.either),
//        validateOptionalField(lastNameFieldName, request.lastName, LastName.either),
//        validatedPhoneNumber,
//        validateOptionalField(addressLine1FieldName, request.addressLine1, AddressLine1.either),
//        validateOptionalField(addressLine2FieldName, request.addressLine2, AddressLine2.either),
//        validateOptionalField(cityFieldName, request.city, City.either),
//        validateOptionalField(postalCodeFieldName, request.postalCode, PostalCode.either),
//        validateOptionalField(companyFieldName, request.company, Company.either),
//      ).mapN(UpdateUserDetails.apply)
//    } yield validatedRequest).fold(_.invalidNec, identity)
//  }
//
//}
