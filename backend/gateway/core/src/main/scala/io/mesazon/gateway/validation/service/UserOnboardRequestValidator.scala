package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class UserOnboardRequestValidator(phoneNumberDomainValidator: PhoneNumberDomainValidator) {

  def validatedOnboardPasswordPostRequest(
      request: smithy.OnboardPasswordPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, OnboardPasswordPostRequest] =
    toValidatedRequestIO(validateOnboardPassword(request))

  def validatedOnboardDetailsPostRequest(
      request: smithy.OnboardDetailsPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, OnboardDetailsPostRequest] =
    toValidatedRequestIO(validateOnboardDetails(request))

  def validatedOnboardVerifyPhoneNumberPostRequest(
      request: smithy.OnboardVerifyPhoneNumberPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, OnboardVerifyPhoneNumberPostRequest] =
    toValidatedRequestIO(validateOnboardVerifyPhoneNumber(request))

  private def validateOnboardPassword(
      request: smithy.OnboardPasswordPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, OnboardPasswordPostRequest]] =
    ZIO.succeed(
      validateRequiredField("password", request.password, Password.either).map(OnboardPasswordPostRequest.apply)
    )

  private def validateOnboardDetails(
      request: smithy.OnboardDetailsPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, OnboardDetailsPostRequest]] =
    phoneNumberDomainValidator
      .validate(request.phoneNumber.phoneCountryCode, request.phoneNumber.phoneNationalNumber)
      .map(phoneNumberValidated =>
        (
          validateRequiredField("fullName", request.fullName, FullName.either),
          phoneNumberValidated,
        ).mapN(OnboardDetailsPostRequest.apply)
      )

  private def validateOnboardVerifyPhoneNumber(
      request: smithy.OnboardVerifyPhoneNumberPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, OnboardVerifyPhoneNumberPostRequest]] =
    ZIO.succeed(
      (
        validateRequiredField("otpID", request.otpID, OtpID.either),
        validateRequiredField("otp", request.otp, Otp.either),
      ).mapN(OnboardVerifyPhoneNumberPostRequest.apply)
    )
}

object UserOnboardRequestValidator {

  val live = ZLayer.derive[UserOnboardRequestValidator]
}
