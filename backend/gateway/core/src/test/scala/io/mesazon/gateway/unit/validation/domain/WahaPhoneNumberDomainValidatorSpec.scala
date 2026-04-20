package io.mesazon.gateway.unit.validation.domain

import cats.data.NonEmptyChain
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import io.mesazon.gateway.utils.PhoneNumberUtil
import io.mesazon.gateway.validation.domain.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class WahaPhoneNumberDomainValidatorSpec extends ZWordSpecBase {

  "WahaPhoneNumberValidator" should {
    "successfully return PhoneNumber when number is valid" in {
      val wahaPhoneNumberCY              = waha.WahaPhone.assume("35799135215")
      val wahaPhoneNumberUK              = waha.WahaPhone.assume("447754737452")
      val wahaPhoneNumberDomainValidator = ZIO
        .service[WahaPhoneNumberDomainValidator]
        .provide(
          WahaPhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
        )
        .zioValue

      wahaPhoneNumberDomainValidator.validate(wahaPhoneNumberCY).zioValue shouldBe PhoneNumber(
        phoneRegion = PhoneRegion.assume("CY"),
        phoneCountryCode = PhoneCountryCode.assume("+357"),
        phoneNationalNumber = PhoneNationalNumber.assume("99135215"),
        phoneNumberE164 = PhoneNumberE164.assume("+35799135215"),
      ).valid

      wahaPhoneNumberDomainValidator.validate(wahaPhoneNumberUK).zioValue shouldBe PhoneNumber(
        phoneRegion = PhoneRegion.assume("GB"),
        phoneCountryCode = PhoneCountryCode.assume("+44"),
        phoneNationalNumber = PhoneNationalNumber.assume("7754737452"),
        phoneNumberE164 = PhoneNumberE164.assume("+447754737452"),
      ).valid
    }

    "fail with parse failure when phone national number contains fewer numbers" in {
      val wahaPhoneNumberIncorrect       = waha.WahaPhone.assume("123")
      val wahaPhoneNumberDomainValidator = ZIO
        .service[WahaPhoneNumberDomainValidator]
        .provide(
          WahaPhoneNumberDomainValidator.live,
          PhoneNumberUtil.live,
        )
        .zioValue

      wahaPhoneNumberDomainValidator.validate(wahaPhoneNumberIncorrect).zioValue shouldBe NonEmptyChain
        .one(
          InvalidFieldError(
            "wahaPhoneNumber",
            "Failed waha phone number validation, wahaPhoneNumber: [123], error: [None]",
            wahaPhoneNumberIncorrect.value,
          )
        )
        .invalid
    }
  }
}
