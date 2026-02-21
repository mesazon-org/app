package io.rikkos.gateway.validation

import cats.syntax.all.*
import io.rikkos.domain.gateway.*
import io.rikkos.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.gateway.smithy
import io.rikkos.gateway.validation.PhoneNumberValidator.PhoneNumberParams
import zio.*
import zio.interop.catz.*

object UserContactsValidators {

  private def upsertUserContactValidator(
      phoneNumberValidator: DomainValidator[PhoneNumberParams, PhoneNumberE164]
  ): DomainValidator[smithy.UpsertUserContactRequest, UpsertUserContact] = { request =>
    phoneNumberValidator
      .validate(request.phoneRegion, request.phoneNationalNumber)
      .map(validatedPhoneNumber =>
        (
          validateOptionalField("userContactID", request.userContactID, UserContactID.either),
          validateRequiredField("displayName", request.displayName, DisplayName.either),
          validateRequiredField("firstName", request.firstName, FirstName.either),
          validatedPhoneNumber,
          validateOptionalField("lastName", request.lastName, LastName.either),
          validateOptionalField("email", request.email, Email.either),
          validateOptionalField("addressLine1", request.addressLine1, AddressLine1.either),
          validateOptionalField("addressLine2", request.addressLine2, AddressLine2.either),
          validateOptionalField("city", request.city, City.either),
          validateOptionalField("postalCode", request.postalCode, PostalCode.either),
          validateOptionalField("company", request.company, Company.either),
        ).mapN(UpsertUserContact.apply)
      )
  }

  private def upsertUserContactsValidator(
      userContactValidator: DomainValidator[smithy.UpsertUserContactRequest, UpsertUserContact]
  ): DomainValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[UpsertUserContact]] =
    requests =>
      ZIO
        .fromOption(NonEmptyChunk.fromIterableOption(requests))
        .flatMap(_.traverse(userContactValidator.validate))
        .map(_.sequence)
        .fold(
          _ =>
            InvalidFieldError(
              "upsertUserContactRequest",
              "request received contained empty collection",
              Seq.empty,
            ).invalidNec,
          identity,
        )

  val upsertUserContactsValidatorLive =
    ZLayer.fromFunction(upsertUserContactValidator) >>> ZLayer.fromFunction(
      upsertUserContactsValidator andThen toServiceValidator
    )
}
