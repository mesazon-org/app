package io.rikkos.gateway.validation

import cats.data.ValidatedNec
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.gateway.config.PhoneNumberValidationConfig
import io.rikkos.gateway.smithy
import zio.*
import zio.interop.catz.*

trait ServiceValidator[A, B] {
  def validate(rawData: A): IO[ServiceError.BadRequestError, B]
}

object ServiceValidator {

  private[gateway] type PhoneNumberParams = (phoneRegion: String, phoneNationalNumber: String)

  private inline val phoneRegionFieldName         = "phoneRegion"
  private inline val phoneNationalNumberFieldName = "phoneNationalNumber"
  private inline val firstNameFieldName           = "firstName"
  private inline val lastNameFieldName            = "lastName"
  private inline val addressLine1FieldName        = "addressLine1"
  private inline val addressLine2FieldName        = "addressLine2"
  private inline val cityFieldName                = "city"
  private inline val postalCodeFieldName          = "postalCode"
  private inline val companyFieldName             = "company"

  trait DomainValidator[A, B] {
    def validate(rawData: A): UIO[ValidatedNec[InvalidFieldError, B]]
  }

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

  // format: off
  private[validation] def phoneNumberValidator(
      config: PhoneNumberValidationConfig,
      phoneNumberUtil: PhoneNumberUtil,
  ): DomainValidator[PhoneNumberParams, PhoneNumber] = {
    case (phoneRegion, phoneNationalNumber) =>
      (for {
        _ <- if (config.supportedRegions.contains(phoneRegion.trim.toUpperCase)) ZIO.unit
        else ZIO.fail((phoneRegionFieldName, s"Phone region [$phoneRegion] provided is not supported, supported regions ${config.supportedRegions.mkString("[", ",", "]")}"))
        phoneNumberRaw <- ZIO
          .blocking(ZIO.attempt(phoneNumberUtil.parse(phoneNationalNumber, phoneRegion)))
          .flatMapError(error =>
            ZIO.fiberId.flatMap(fid =>
              ZIO.logDebugCause(s"Failed national number [$phoneNationalNumber] with region [$phoneRegion] parse, supported regions ${config.supportedRegions.mkString("[", ",", "]")}", Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)))
            ) *> ZIO.succeed((phoneNationalNumberFieldName, s"Phone national number [$phoneNationalNumber] provided with region [$phoneRegion] failed to parse, supported regions ${config.supportedRegions.mkString("[", ",", "]")}"))
          )
        phoneNumberE164 <- if (phoneNumberUtil.isValidNumber(phoneNumberRaw)) ZIO.attempt(phoneNumberUtil.format(phoneNumberRaw, PhoneNumberFormat.E164)).orDie
        else ZIO.fail((phoneNationalNumberFieldName, s"Phone national number [$phoneNationalNumber] raw [${phoneNumberRaw}] provided with region [$phoneRegion] failed to be validated, supported regions ${config.supportedRegions.mkString("[", ",", "]")}"))
        phoneNumber <- ZIO
          .fromEither(PhoneNumber.either(phoneNumberE164))
          .mapError(errorMessage => (phoneNationalNumberFieldName, errorMessage))
      } yield phoneNumber).fold(_.invalidNec, _.validNec)
  }
  // format: on

  private[validation] def onboardUserDetailsRequestValidator(
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

  private[validation] def updateUserDetailsRequestValidator(
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

  private def toServiceValidator[A, B](
      domainValidator: DomainValidator[A, B]
  ): ServiceValidator[A, B] = rawData =>
    domainValidator
      .validate(rawData)
      .flatMap(validated => ZIO.fromEither(validated.toEither))
      .mapError(_.toNonEmptyList.toList)
      .mapError(ServiceError.BadRequestError.FormValidationError.apply)

  val phoneNumberValidatorLive = ZLayer.fromFunction(phoneNumberValidator)

  val onboardUserDetailsRequestValidatorLive =
    ZLayer.fromFunction(onboardUserDetailsRequestValidator andThen toServiceValidator)

  val updateUserDetailsRequestValidatorLive =
    ZLayer.fromFunction(updateUserDetailsRequestValidator andThen toServiceValidator)
}
