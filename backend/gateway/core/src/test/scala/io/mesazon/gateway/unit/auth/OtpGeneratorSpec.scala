package io.mesazon.gateway.unit.auth

import io.mesazon.gateway.auth.OtpGenerator
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class OtpGeneratorSpec extends ZWordSpecBase {

  "OtpGenerator" should {
    "generate a valid Otp" in {
      val otpUtil = ZIO
        .service[OtpGenerator]
        .provide(OtpGenerator.live)
        .zioValue

      val otp = otpUtil.generate.zioValue

      assert(otp.value.length == 6, s"Expected Otp length to be 6, but got ${otp.value.length}")
      assert(
        otp.value.forall(c => c.isDigit || c.isUpper),
        s"Expected Otp to contain only uppercase letters and digits, but got ${otp.value}",
      )
    }
  }

}
