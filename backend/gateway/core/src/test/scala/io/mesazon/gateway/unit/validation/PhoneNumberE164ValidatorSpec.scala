package io.mesazon.gateway.unit.validation

import cats.data.NonEmptyChain
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.gateway.validation.*
import io.mesazon.gateway.validation.PhoneNumberValidator.PhoneNumberRegion
import io.mesazon.testkit.base.ZWordSpecBase
import zio.{ZIO, ZLayer}

class PhoneNumberE164ValidatorSpec extends ZWordSpecBase {

  val config          = PhoneNumberValidatorConfig(supportedRegions = Set("CY", "GB"))
  val phoneNumberUtil = PhoneNumberUtil.getInstance()

  "PhoneNumberValidator" when {
    "phone region and phone national number is provided" should {
      "return successful phone number when is valid and supported" in {
        val phoneRegion          = "CY"
        val phoneNationalNumber  = "99135215"
        val phoneNumberValidator = ZIO
          .service[DomainValidator[PhoneNumberRegion, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.phoneNumberRegionValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe PhoneNumberE164
          .assume("+35799135215")
          .valid
      }

      "return successful phone number when is valid and supported in E164 format" in {
        val phoneRegion          = "GB"
        val phoneNationalNumber1 = "07754737552"
        val phoneNationalNumber2 = "7754737552"
        val phoneNumberValidator = ZIO
          .service[DomainValidator[PhoneNumberRegion, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.phoneNumberRegionValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        val expectedResult = PhoneNumberE164.assume("+447754737552")

        phoneNumberValidator.validate(phoneRegion, phoneNationalNumber1).zioValue shouldBe expectedResult.valid
        phoneNumberValidator.validate(phoneRegion, phoneNationalNumber2).zioValue shouldBe expectedResult.valid
      }

      "fail with invalid region and national number if region is not supported" in {
        val phoneRegion          = "GA"
        val phoneNationalNumber  = "13123123"
        val phoneNumberValidator = ZIO
          .service[DomainValidator[PhoneNumberRegion, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.phoneNumberRegionValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe NonEmptyChain(
          InvalidFieldError(
            "phoneRegion",
            "Phone region [GA] provided is not supported, supported regions [CY,GB]",
            phoneRegion,
          ),
          InvalidFieldError("phoneNationalNumber", "Phone national number could not be validated", phoneNationalNumber),
        ).invalid
      }

      "fail with parse failure when phone national number contains fewer numbers" in {
        val phoneRegion          = "CY"
        val phoneNationalNumber  = "1"
        val phoneNumberValidator = ZIO
          .service[DomainValidator[PhoneNumberRegion, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.phoneNumberRegionValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe NonEmptyChain
          .one(
            InvalidFieldError(
              "phoneNationalNumber",
              "Phone national number [1] provided with region [CY] failed to parse, supported regions [CY,GB]",
              phoneNationalNumber,
            )
          )
          .invalid
      }

      "fail with validation failure when phone national number is wrong" in {
        val phoneRegion          = "CY"
        val phoneNationalNumber  = "93123123"
        val phoneNumberValidator = ZIO
          .service[DomainValidator[PhoneNumberRegion, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.phoneNumberRegionValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe NonEmptyChain
          .one(
            InvalidFieldError(
              "phoneNationalNumber",
              "Phone national number [93123123] raw [Country Code: 357 National Number: 93123123] provided with region [CY] failed to be validated, supported regions [CY,GB]",
              phoneNationalNumber,
            )
          )
          .invalid
      }
    }

    "waha phone number is provided" should {
      "return successfull when number exist" in {
        val wahaPhoneNumberCY        = waha.WahaPhone.assume("35799135215")
        val wahaPhoneNumberUK        = waha.WahaPhone.assume("447754737452")
        val wahaPhoneNumberValidator = ZIO
          .service[DomainValidator[waha.WahaPhone, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.wahaPhoneNumberValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        wahaPhoneNumberValidator.validate(wahaPhoneNumberCY).zioValue shouldBe PhoneNumberE164
          .assume("+35799135215")
          .valid

        wahaPhoneNumberValidator.validate(wahaPhoneNumberUK).zioValue shouldBe PhoneNumberE164
          .assume("+447754737452")
          .valid
      }

      "fail with not supported region when number region is not presented in config" in {
        val wahaPhoneNumberUS        = waha.WahaPhone.assume("12135332733")
        val wahaPhoneNumberValidator = ZIO
          .service[DomainValidator[waha.WahaPhone, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.wahaPhoneNumberValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        wahaPhoneNumberValidator.validate(wahaPhoneNumberUS).zioValue shouldBe NonEmptyChain
          .one(
            InvalidFieldError(
              "phoneNumber",
              "Waha phone number [12135332733] raw [Country Code: 1 National Number: 2135332733] provided with region [US] failed to be validated, supported regions [CY,GB]",
              wahaPhoneNumberUS.value,
            )
          )
          .invalid
      }

      "fail with parse failure when phone national number contains fewer numbers" in {
        val wahaPhoneNumberIncorrect = waha.WahaPhone.assume("123")
        val wahaPhoneNumberValidator = ZIO
          .service[DomainValidator[waha.WahaPhone, PhoneNumberE164]]
          .provide(
            PhoneNumberValidator.wahaPhoneNumberValidatorLive,
            ZLayer.succeed(config),
            ZLayer.succeed(phoneNumberUtil),
          )
          .zioValue

        wahaPhoneNumberValidator.validate(wahaPhoneNumberIncorrect).zioValue shouldBe NonEmptyChain
          .one(
            InvalidFieldError(
              "phoneNumber",
              "Waha phone number [123] raw [Country Code: 1 National Number: 23] provided failed to be validated, supported regions [CY,GB]",
              wahaPhoneNumberIncorrect.value,
            )
          )
          .invalid
      }
    }
  }
}
