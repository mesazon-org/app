package io.mesazon.gateway.unit.validation.domain

import cats.data.NonEmptyChain
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.utils.PhoneNumberUtil
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.domain.PhoneNumberDomainValidator.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.{ZIO, ZLayer}

class PhoneNumberDomainValidatorSpec extends ZWordSpecBase {

  val config = PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))

  "PhoneNumberValidator" should {
    "successfully validate phone number" in {
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberRaw, PhoneNumber]]
        .provide(
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(config),
        )
        .zioValue

      phoneNumberValidator.validate("+357", "99135215").zioValue shouldBe PhoneNumber(
        PhoneRegion.assume("CY"),
        PhoneCountryCode.assume("+357"),
        PhoneNationalNumber.assume("99135215"),
        PhoneNumberE164.assume("+35799135215"),
      ).valid

      phoneNumberValidator.validate("+44", "7734737752").zioValue shouldBe PhoneNumber(
        PhoneRegion.assume("GB"),
        PhoneCountryCode.assume("+44"),
        PhoneNationalNumber.assume("7734737752"),
        PhoneNumberE164.assume("+447734737752"),
      ).valid

      phoneNumberValidator.validate(" +35 7", "99 135 215 ").zioValue shouldBe PhoneNumber(
        PhoneRegion.assume("CY"),
        PhoneCountryCode.assume("+357"),
        PhoneNationalNumber.assume("99135215"),
        PhoneNumberE164.assume("+35799135215"),
      ).valid

      phoneNumberValidator.validate(" +4 4 ", "77 347 77652 ").zioValue shouldBe PhoneNumber(
        PhoneRegion.assume("GB"),
        PhoneCountryCode.assume("+44"),
        PhoneNationalNumber.assume("7734777652"),
        PhoneNumberE164.assume("+447734777652"),
      ).valid
    }

    "fail with not supported country code when phone country code region is not supported in the config" in {
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberRaw, PhoneNumber]]
        .provide(
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(config),
        )
        .zioValue

      phoneNumberValidator.validate("+1", "2135332733").zioValue shouldBe NonEmptyChain(
        InvalidFieldError(
          "phoneCountryCode",
          "PhoneRegion is not supported, phoneCountryCode: [+1], phoneNationalNumber: [2135332733], supportedPhoneRegions: [CY,GB], error: [None]",
          "+1",
        ),
        InvalidFieldError(
          "phoneNationalNumber",
          "PhoneNationalNumber could not be validated due to phone region is not supported, phoneCountryCode: [+1], phoneNationalNumber: [2135332733], supportedPhoneRegions: [CY,GB], error: [None]",
          "2135332733",
        ),
      ).invalid
    }

    "fail with parse error when phone national number is incorrect" in {
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberRaw, PhoneNumber]]
        .provide(
          PhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
          ZLayer.succeed(config),
        )
        .zioValue

      phoneNumberValidator.validate(" +4 4 ", "77 347 7752 ").zioValue shouldBe NonEmptyChain(
        InvalidFieldError(
          "phoneNationalNumber",
          "PhoneNationalNumber could not be validated using phoneNumberUtil, phoneCountryCode: [ +4 4 ], phoneNationalNumber: [77 347 7752 ], supportedPhoneRegions: [CY,GB], error: [None]",
          "77 347 7752 ",
        )
      ).invalid
    }
  }
}
