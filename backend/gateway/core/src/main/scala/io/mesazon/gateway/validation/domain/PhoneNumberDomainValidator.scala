package io.mesazon.gateway.validation.domain

import cats.data.*
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.*
import io.mesazon.gateway.validation.domain.PhoneNumberDomainValidator.*
import zio.*

final class PhoneNumberDomainValidator(
    phoneNumberValidatorConfig: PhoneNumberValidatorConfig,
    phoneNumberUtil: PhoneNumberUtil,
) extends DomainValidator[PhoneNumberRaw, PhoneNumber] {

  private def invalidFieldErrorMessage(
      message: String,
      phoneCountryCodeRaw: String,
      phoneNationalNumberRaw: String,
      supportedPhoneRegions: Set[String],
      errorMessage: Option[String] = None,
  ): String =
    s"$message, phoneCountryCode: [$phoneCountryCodeRaw], phoneNationalNumber: [$phoneNationalNumberRaw], supportedPhoneRegions: ${supportedPhoneRegions.mkString("[", ",", "]")}, error: [$errorMessage]"

  override def validate(phoneNumberRaw: PhoneNumberRaw): UIO[ValidatedNec[InvalidFieldError, PhoneNumber]] = (for {
    phoneCountryCode <- ZIO
      .fromEither(PhoneCountryCode.either(phoneNumberRaw.phoneCountryCodeRaw.filterNot(_.isWhitespace)))
      .mapError(errorMessage =>
        NonEmptyChain(
          InvalidFieldError(
            "phoneCountryCode",
            invalidFieldErrorMessage(
              "PhoneCountryCode could not be construct",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(errorMessage),
            ),
            phoneNumberRaw.phoneCountryCodeRaw,
          ),
          InvalidFieldError(
            "phoneNationalNumber",
            invalidFieldErrorMessage(
              "PhoneNationalNumber could not be validated due to phone country code parse failure",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(errorMessage),
            ),
            phoneNumberRaw.phoneNationalNumberRaw,
          ),
        )
      )
    phoneNumberProto <- ZIO
      .attempt(phoneNumberUtil.parse(s"${phoneCountryCode.value}${phoneNumberRaw.phoneNationalNumberRaw}", null))
      .flatMapError(error =>
        ZIO.fiberId.flatMap(fid =>
          ZIO.logDebugCause(
            s"PhoneNationalNumber failed [$phoneNumberRaw.phoneNationalNumberRaw] with country code [$phoneNumberRaw.phoneCountryCodeRaw] parse, supported phone regions ${phoneNumberValidatorConfig.supportedPhoneRegions.mkString("[", ",", "]")}",
            Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)),
          )
        ) *> ZIO.succeed(
          NonEmptyChain(
            InvalidFieldError(
              "phoneNationalNumber",
              invalidFieldErrorMessage(
                "PhoneNationalNumber with country code could not be parsed using phone number util",
                phoneNumberRaw.phoneCountryCodeRaw,
                phoneNumberRaw.phoneNationalNumberRaw,
                phoneNumberValidatorConfig.supportedPhoneRegions,
                Some(error.getMessage),
              ),
              phoneNumberRaw.phoneNationalNumberRaw,
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
              "phoneNationalNumber",
              invalidFieldErrorMessage(
                "PhoneNationalNumber could not be validated using phoneNumberUtil",
                phoneNumberRaw.phoneCountryCodeRaw,
                phoneNumberRaw.phoneNationalNumberRaw,
                phoneNumberValidatorConfig.supportedPhoneRegions,
              ),
              phoneNumberRaw.phoneNationalNumberRaw,
            )
          )
        )
    phoneNumberE164 <- ZIO
      .fromEither(PhoneNumberE164.either(phoneNumberE164Raw))
      .mapError(errorMessage =>
        NonEmptyChain(
          InvalidFieldError(
            "phoneNationalNumber",
            invalidFieldErrorMessage(
              "PhoneNationalNumber could not be construct",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(errorMessage),
            ),
            phoneNumberRaw.phoneNationalNumberRaw,
          )
        )
      )
    phoneNationalNumberFormatted <- ZIO
      .attempt(phoneNumberUtil.getNationalSignificantNumber(phoneNumberProto))
      .mapError(error =>
        NonEmptyChain(
          InvalidFieldError(
            "phoneNationalNumber",
            invalidFieldErrorMessage(
              "PhoneNationalNumber failed to getNationalSignificantNumber",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(error.getMessage),
            ),
            phoneNumberRaw.phoneNationalNumberRaw,
          )
        )
      )
    phoneNationalNumber <- ZIO
      .fromEither(PhoneNationalNumber.either(phoneNationalNumberFormatted))
      .mapError(errorMessage =>
        NonEmptyChain(
          InvalidFieldError(
            "phoneNationalNumber",
            invalidFieldErrorMessage(
              "PhoneNationalNumber could not be construct",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(errorMessage),
            ),
            phoneNumberRaw.phoneNationalNumberRaw,
          )
        )
      )
    phoneRegionRaw <- ZIO
      .attempt(phoneNumberUtil.getRegionCodeForNumber(phoneNumberProto))
      .mapError(error =>
        NonEmptyChain(
          InvalidFieldError(
            "phoneNationalNumber",
            invalidFieldErrorMessage(
              "PhoneNationalNumber failed to getRegionCodeForNumber",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(error.getMessage),
            ),
            phoneNumberRaw.phoneNationalNumberRaw,
          )
        )
      )
    phoneRegion <- ZIO
      .fromEither(PhoneRegion.either(phoneRegionRaw))
      .mapError(errorMessage =>
        NonEmptyChain(
          InvalidFieldError(
            "phoneCountryCode",
            invalidFieldErrorMessage(
              "PhoneRegion could not be construct",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(errorMessage),
            ),
            phoneNumberRaw.phoneCountryCodeRaw,
          ),
          InvalidFieldError(
            "phoneNationalNumber",
            invalidFieldErrorMessage(
              "PhoneNationalNumber could not be validated due to phone region parse failure",
              phoneNumberRaw.phoneCountryCodeRaw,
              phoneNumberRaw.phoneNationalNumberRaw,
              phoneNumberValidatorConfig.supportedPhoneRegions,
              Some(errorMessage),
            ),
            phoneNumberRaw.phoneNationalNumberRaw,
          ),
        )
      )
    _ <-
      if (phoneNumberValidatorConfig.supportedPhoneRegions.contains(phoneRegion.value)) ZIO.unit
      else
        ZIO.fail(
          NonEmptyChain(
            InvalidFieldError(
              "phoneCountryCode",
              invalidFieldErrorMessage(
                "PhoneRegion is not supported",
                phoneNumberRaw.phoneCountryCodeRaw,
                phoneNumberRaw.phoneNationalNumberRaw,
                phoneNumberValidatorConfig.supportedPhoneRegions,
              ),
              phoneNumberRaw.phoneCountryCodeRaw,
            ),
            InvalidFieldError(
              "phoneNationalNumber",
              invalidFieldErrorMessage(
                "PhoneNationalNumber could not be validated due to phone region is not supported",
                phoneNumberRaw.phoneCountryCodeRaw,
                phoneNumberRaw.phoneNationalNumberRaw,
                phoneNumberValidatorConfig.supportedPhoneRegions,
              ),
              phoneNumberRaw.phoneNationalNumberRaw,
            ),
          )
        )
  } yield PhoneNumber(phoneRegion, phoneCountryCode, phoneNationalNumber, phoneNumberE164)).fold(_.invalid, _.valid)
}

object PhoneNumberDomainValidator {
  type PhoneNumberRaw = (phoneCountryCodeRaw: String, phoneNationalNumberRaw: String)

  val live = ZLayer.derive[PhoneNumberDomainValidator]
}
