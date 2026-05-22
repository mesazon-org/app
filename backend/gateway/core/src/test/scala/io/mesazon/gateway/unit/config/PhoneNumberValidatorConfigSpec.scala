package io.mesazon.gateway.unit.config

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.mesazon.gateway.config.PhoneNumberValidatorConfig
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class PhoneNumberValidatorConfigSpec extends ZWordSpecBase {

  "PhoneNumberValidatorConfig" when {
    "supportedRegionsConfig" should {
      "return valid config with all supported regions that are provided" in {
        val config = PhoneNumberValidatorConfig(supportedPhoneRegions = Set("US", "GB", "CY"))

        val resConfig = ZIO
          .service[PhoneNumberValidatorConfig]
          .provide(
            ZLayer.succeed(config) >>> PhoneNumberValidatorConfig.supportedRegionsConfig,
            ZLayer.succeed(PhoneNumberUtil.getInstance()),
          )
          .zioValue

        resConfig shouldBe config
      }

      "fail with unsupported regions that have been provided" in {
        val config = PhoneNumberValidatorConfig(supportedPhoneRegions = Set("UU", "GAMW", "CY"))

        val error = ZIO
          .service[PhoneNumberValidatorConfig]
          .provide(
            ZLayer.succeed(config) >>> PhoneNumberValidatorConfig.supportedRegionsConfig,
            ZLayer.succeed(PhoneNumberUtil.getInstance()),
          )
          .zioError

        error.path shouldBe Chunk("validation", "supported-phone-regions")
        error.message shouldBe "Config pass unsupported regions [UU, GAMW]"
      }
    }
  }
}
