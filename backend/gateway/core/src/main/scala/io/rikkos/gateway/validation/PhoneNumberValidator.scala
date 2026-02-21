package io.rikkos.gateway.validation

import cats.data.*
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.rikkos.domain.gateway.*
import io.rikkos.gateway.config.PhoneNumberValidatorConfig
import zio.*

object PhoneNumberValidator {
  type PhoneNumberParams = (phoneRegion: String, phoneNationalNumber: String)

  private def phoneNumberValidator(
      config: PhoneNumberValidatorConfig,
      phoneNumberUtil: PhoneNumberUtil,
  ): DomainValidator[PhoneNumberParams, PhoneNumber] = { case (phoneRegion, phoneNationalNumber) =>
    (for {
      _ <-
        if (config.supportedRegions.contains(phoneRegion.trim.toUpperCase)) ZIO.unit
        else
          ZIO.fail(
            NonEmptyChain(
              (
                phoneRegionFieldName,
                s"Phone region [$phoneRegion] provided is not supported, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
              ),
              (phoneNationalNumberFieldName, "Phone national number could not be validated"),
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
              (
                phoneNationalNumberFieldName,
                s"Phone national number [$phoneNationalNumber] provided with region [$phoneRegion] failed to parse, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
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
              (
                phoneNationalNumberFieldName,
                s"Phone national number [$phoneNationalNumber] raw [${phoneNumberRaw}] provided with region [$phoneRegion] failed to be validated, supported regions ${config.supportedRegions.mkString("[", ",", "]")}",
              )
            )
          )
      phoneNumber <- ZIO
        .fromEither(PhoneNumber.either(phoneNumberE164))
        .mapError(errorMessage =>
          NonEmptyChain(
            (phoneNationalNumberFieldName, errorMessage)
          )
        )
    } yield phoneNumber).fold(_.invalid, _.valid)
  }

  val phoneNumberValidatorLive = ZLayer.fromFunction(phoneNumberValidator)
}
