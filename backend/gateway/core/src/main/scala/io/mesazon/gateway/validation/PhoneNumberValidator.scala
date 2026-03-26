package io.mesazon.gateway.validation

import cats.data.*
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import zio.*

object PhoneNumberValidator {
  type PhoneNumberRegion = (phoneRegion: String, phoneNationalNumber: String)

  private def phoneNumberRegionValidator(
      config: PhoneNumberValidatorConfig,
      phoneNumberUtil: PhoneNumberUtil,
  ): DomainValidator[PhoneNumberRegion, PhoneNumberE164] = { case (phoneRegion, phoneNationalNumber) =>
    (for {
      _ <-
        if (config.supportedRegions.contains(phoneRegion.trim.toUpperCase)) ZIO.unit
        else
          ZIO.fail(
            NonEmptyChain(
              InvalidFieldError(
                "phoneRegion",
                s"Phone region [$phoneRegion] provided is not supported, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
                phoneRegion,
              ),
              InvalidFieldError(
                "phoneNationalNumber",
                "Phone national number could not be validated",
                phoneNationalNumber,
              ),
            )
          )
      phoneNumberRaw <- ZIO
        .attemptBlocking(phoneNumberUtil.parse(phoneNationalNumber, phoneRegion))
        .flatMapError(error =>
          ZIO.fiberId.flatMap(fid =>
            ZIO.logDebugCause(
              s"Failed national number [$phoneNationalNumber] with region [$phoneRegion] parse, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
              Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)),
            )
          ) *> ZIO.succeed(
            NonEmptyChain(
              InvalidFieldError(
                "phoneNationalNumber",
                s"Phone national number [$phoneNationalNumber] provided with region [$phoneRegion] failed to parse, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
                phoneNationalNumber,
              )
            )
          )
        )
      phoneNumberE164 <-
        if (phoneNumberUtil.isValidNumber(phoneNumberRaw))
          ZIO.attempt(phoneNumberUtil.format(phoneNumberRaw, PhoneNumberFormat.E164)).orDie
        else
          ZIO.fail(
            NonEmptyChain(
              InvalidFieldError(
                "phoneNationalNumber",
                s"Phone national number [$phoneNationalNumber] raw [${phoneNumberRaw}] provided with region [$phoneRegion] failed to be validated, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
                phoneNationalNumber,
              )
            )
          )
      phoneNumber <- ZIO
        .fromEither(PhoneNumberE164.either(phoneNumberE164))
        .mapError(errorMessage =>
          NonEmptyChain(
            InvalidFieldError("phoneNationalNumber", errorMessage, phoneNationalNumber)
          )
        )
    } yield phoneNumber).fold(_.invalid, _.valid)
  }

  private def wahaPhoneNumberValidator(
      config: PhoneNumberValidatorConfig,
      phoneNumberUtil: PhoneNumberUtil,
  ): DomainValidator[waha.WahaPhone, PhoneNumberE164] = { wahaPhoneNumber =>
    (for {
      phoneNumberProto <- ZIO
        .attemptBlocking(phoneNumberUtil.parse(s"+${wahaPhoneNumber.value}", null))
        .flatMapError(error =>
          ZIO.fiberId.flatMap(fid =>
            ZIO.logDebugCause(
              s"Failed waha phone number [${wahaPhoneNumber.value}] validation, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
              Cause.die(error, StackTrace.fromJava(fid, error.getStackTrace)),
            )
          ) *> ZIO.succeed(
            NonEmptyChain(
              InvalidFieldError(
                "phoneNumber",
                s"Waha phone number [${wahaPhoneNumber.value}] provided failed to parse, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
                wahaPhoneNumber.value,
              )
            )
          )
        )
      phoneNumberE164 <-
        if (phoneNumberUtil.isValidNumber(phoneNumberProto))
          ZIO.attempt(phoneNumberUtil.format(phoneNumberProto, PhoneNumberFormat.E164)).orDie
        else
          ZIO.fail(
            NonEmptyChain(
              InvalidFieldError(
                "phoneNumber",
                s"Waha phone number [${wahaPhoneNumber.value}] raw [$phoneNumberProto] provided failed to be validated, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
                wahaPhoneNumber.value,
              )
            )
          )
      phoneRegion = phoneNumberUtil.getRegionCodeForNumber(phoneNumberProto)
      _ <-
        if (config.supportedRegions.contains(phoneRegion))
          ZIO.unit
        else
          ZIO.fail(
            NonEmptyChain(
              InvalidFieldError(
                "phoneNumber",
                s"Waha phone number [${wahaPhoneNumber.value}] raw [$phoneNumberProto] provided with region [$phoneRegion] failed to be validated, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
                wahaPhoneNumber.value,
              )
            )
          )
      phoneNumber <- ZIO
        .fromEither(PhoneNumberE164.either(phoneNumberE164))
        .mapError(errorMessage =>
          NonEmptyChain(
            InvalidFieldError("phoneNumber", errorMessage, wahaPhoneNumber.value)
          )
        )
    } yield phoneNumber).fold(_.invalid, _.valid)
  }

  val phoneNumberRegionValidatorLive = ZLayer.fromFunction(phoneNumberRegionValidator)

  val wahaPhoneNumberValidatorLive = ZLayer.fromFunction(wahaPhoneNumberValidator)
}
