package io.rikkos.gateway.unit

import cats.data.NonEmptyChain
import cats.syntax.all.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.rikkos.domain.PhoneNumber
import io.rikkos.gateway.config.PhoneNumberValidatorConfig
import io.rikkos.gateway.validation.*
import io.rikkos.gateway.validation.PhoneNumberValidator.PhoneNumberParams
import io.rikkos.testkit.base.ZWordSpecBase
import zio.{ZIO, ZLayer}

class PhoneNumberValidatorSpec extends ZWordSpecBase {

  val config          = PhoneNumberValidatorConfig(supportedRegions = Set("CY", "GB"))
  val phoneNumberUtil = PhoneNumberUtil.getInstance()

  "PhoneNumberValidator" should {
    "return successful phone number when is valid and supported" in {
      val phoneRegion         = "CY"
      val phoneNationalNumber = "99135215"
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberParams, PhoneNumber]]
        .provide(PhoneNumberValidator.phoneNumberValidatorLive, ZLayer.succeed(config), ZLayer.succeed(phoneNumberUtil))
        .zioValue

      phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe PhoneNumber
        .assume("+35799135215")
        .valid
    }

    "return successful phone number when is valid and supported in E164 format" in {
      val phoneRegion          = "GB"
      val phoneNationalNumber1 = "07754737552"
      val phoneNationalNumber2 = "7754737552"
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberParams, PhoneNumber]]
        .provide(PhoneNumberValidator.phoneNumberValidatorLive, ZLayer.succeed(config), ZLayer.succeed(phoneNumberUtil))
        .zioValue

      val expectedResult = PhoneNumber.assume("+447754737552")

      phoneNumberValidator.validate(phoneRegion, phoneNationalNumber1).zioValue shouldBe expectedResult.valid
      phoneNumberValidator.validate(phoneRegion, phoneNationalNumber2).zioValue shouldBe expectedResult.valid
    }

    "fail with invalid region and national number if region is not supported" in {
      val phoneRegion         = "GA"
      val phoneNationalNumber = "13123123"
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberParams, PhoneNumber]]
        .provide(PhoneNumberValidator.phoneNumberValidatorLive, ZLayer.succeed(config), ZLayer.succeed(phoneNumberUtil))
        .zioValue

      phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe NonEmptyChain(
        ("phoneRegion", "Phone region [GA] provided is not supported, supported regions [CY,GB]"),
        ("phoneNationalNumber", "Phone national number could not be validated"),
      ).invalid
    }

    "fail with parse failure when phone national number contains fewer numbers" in {
      val phoneRegion         = "CY"
      val phoneNationalNumber = "1"
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberParams, PhoneNumber]]
        .provide(PhoneNumberValidator.phoneNumberValidatorLive, ZLayer.succeed(config), ZLayer.succeed(phoneNumberUtil))
        .zioValue

      phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe NonEmptyChain
        .one(
          (
            "phoneNationalNumber",
            "Phone national number [1] provided with region [CY] failed to parse, supported regions [CY,GB]",
          )
        )
        .invalid
    }

    "fail with validation failure when phone national number is wrong" in {
      val phoneRegion         = "CY"
      val phoneNationalNumber = "93123123"
      val phoneNumberValidator = ZIO
        .service[DomainValidator[PhoneNumberParams, PhoneNumber]]
        .provide(PhoneNumberValidator.phoneNumberValidatorLive, ZLayer.succeed(config), ZLayer.succeed(phoneNumberUtil))
        .zioValue

      phoneNumberValidator.validate(phoneRegion, phoneNationalNumber).zioValue shouldBe NonEmptyChain
        .one(
          (
            "phoneNationalNumber",
            "Phone national number [93123123] raw [Country Code: 357 National Number: 93123123] provided with region [CY] failed to be validated, supported regions [CY,GB]",
          )
        )
        .invalid
    }
  }
}
