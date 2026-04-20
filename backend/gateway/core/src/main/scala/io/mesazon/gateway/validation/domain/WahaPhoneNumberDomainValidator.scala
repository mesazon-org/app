package io.mesazon.gateway.validation.domain

import cats.data.*
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import zio.*

final class WahaPhoneNumberDomainValidator(
    phoneNumberUtil: PhoneNumberUtil
) extends DomainValidator[waha.WahaPhoneNumber, PhoneNumber] {

  private def invalidFieldErrorMessage(
      message: String,
      wahaPhoneNumber: waha.WahaPhoneNumber,
      errorMessage: Option[String] = None,
  ): String =
    s"$message, wahaPhoneNumber: [${wahaPhoneNumber.value}], error: [$errorMessage]"

  override def validate(
      wahaPhoneNumber: waha.WahaPhoneNumber
  ): UIO[ValidatedNec[BadRequestError.InvalidFieldError, PhoneNumber]] =
    (for {
      phoneNumberProto <- ZIO
        .attempt(phoneNumberUtil.parse(s"+${wahaPhoneNumber.value}", null))
        .flatMapError(error =>
          ZIO.fiberId.flatMap(fid =>
            ZIO.logDebugCause(
              s"Failed waha phone number [${wahaPhoneNumber.value}] validation",
              Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)),
            )
          ) *> ZIO.succeed(
            NonEmptyChain(
              InvalidFieldError(
                "wahaPhoneNumber",
                invalidFieldErrorMessage(
                  "Failed to parse waha phone number",
                  wahaPhoneNumber,
                  Some(error.getMessage),
                ),
                wahaPhoneNumber.value,
              )
            )
          )
        )
      phoneNumberE164Raw <-
        if (phoneNumberUtil.isValidNumber(phoneNumberProto))
          ZIO.attempt(phoneNumberUtil.format(phoneNumberProto, PhoneNumberFormat.E164)).orDie
        else
          ZIO.fail(
            NonEmptyChain(
              InvalidFieldError(
                "wahaPhoneNumber",
                invalidFieldErrorMessage(
                  "Failed waha phone number validation",
                  wahaPhoneNumber,
                ),
                wahaPhoneNumber.value,
              )
            )
          )
      phoneNumberE164 <- ZIO
        .fromEither(PhoneNumberE164.either(phoneNumberE164Raw))
        .mapError(errorMessage =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "Failed PhoneNumberE164 countructor",
                wahaPhoneNumber,
                Some(errorMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
      phoneRegionRaw <- ZIO
        .attempt(phoneNumberUtil.getRegionCodeForNumber(phoneNumberProto))
        .mapError(error =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "Waha phone failed to getRegionCodeForNumber",
                wahaPhoneNumber,
                Some(error.getMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
      phoneRegion <- ZIO
        .fromEither(PhoneRegion.either(phoneRegionRaw))
        .mapError(errorMessage =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "PhoneRegion could not be construct",
                wahaPhoneNumber,
                Some(errorMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
      phoneNationalNumberFormatted <- ZIO
        .attempt(phoneNumberUtil.getNationalSignificantNumber(phoneNumberProto))
        .mapError(error =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "Waha phone failed to getNationalSignificantNumber",
                wahaPhoneNumber,
                Some(error.getMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
      phoneNationalNumber <- ZIO
        .fromEither(
          PhoneNationalNumber.either(
            phoneNationalNumberFormatted
          )
        )
        .mapError(errorMessage =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "Failed to construct PhoneNationalNumber",
                wahaPhoneNumber,
                Some(errorMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
      phoneCountryCodeRaw <- ZIO
        .attempt(phoneNumberProto.getCountryCode)
        .mapError(error =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "Waha phone failed to get country code",
                wahaPhoneNumber,
                Some(error.getMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
      phoneCountryCode <- ZIO
        .fromEither(PhoneCountryCode.either(s"+$phoneCountryCodeRaw"))
        .mapError(errorMessage =>
          NonEmptyChain(
            InvalidFieldError(
              "wahaPhoneNumber",
              invalidFieldErrorMessage(
                "Failed to construct PhoneCountryCode",
                wahaPhoneNumber,
                Some(errorMessage),
              ),
              wahaPhoneNumber.value,
            )
          )
        )
    } yield PhoneNumber(phoneRegion, phoneCountryCode, phoneNationalNumber, phoneNumberE164)).fold(_.invalid, _.valid)

}

object WahaPhoneNumberDomainValidator {

  val live = ZLayer.derive[WahaPhoneNumberDomainValidator]
}
