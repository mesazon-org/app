package io.rikkos.gateway.unit

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.rikkos.gateway.config.PhoneNumberValidatorConfig
import io.rikkos.testkit.base.ZWordSpecBase
import zio.*

class PhoneNumberValidatorConfigSpec extends ZWordSpecBase {

  "PhoneNumberValidatorConfig" when {
    "supportedRegionsConfig" should {
      "return valid config with all supported regions that are provided" in {
        val config = PhoneNumberValidatorConfig(supportedRegions = Set("US", "GB", "CY"))

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
        val config = PhoneNumberValidatorConfig(supportedRegions = Set("UU", "GAMW", "CY"))

        val error = ZIO
          .service[PhoneNumberValidatorConfig]
          .provide(
            ZLayer.succeed(config) >>> PhoneNumberValidatorConfig.supportedRegionsConfig,
            ZLayer.succeed(PhoneNumberUtil.getInstance()),
          )
          .zioError

        error.path shouldBe Chunk("validation", "supported-regions")
        error.message shouldBe "Config pass unsupported regions [UU, GAMW]"
      }
    }
  }
}
