package io.rikkos.gateway.validation

import cats.data.*
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.rikkos.domain.gateway.*
import io.rikkos.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.gateway.config.PhoneNumberValidatorConfig
import zio.*

object PhoneNumberValidator {
  type PhoneNumberParams = (phoneRegion: String, phoneNationalNumber: String)

  private def phoneNumberValidator(
      config: PhoneNumberValidatorConfig,
      phoneNumberUtil: PhoneNumberUtil,
  ): DomainValidator[PhoneNumberParams, PhoneNumberE164] = { case (phoneRegion, phoneNationalNumber) =>
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

  val phoneNumberValidatorLive = ZLayer.fromFunction(phoneNumberValidator)
}
