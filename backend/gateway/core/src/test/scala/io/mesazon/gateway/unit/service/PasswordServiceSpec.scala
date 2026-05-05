package io.mesazon.gateway.unit.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.PasswordConfig
import io.mesazon.gateway.service.*
import io.mesazon.testkit.base.*
import zio.*

class PasswordServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  val passwordConfig = PasswordConfig(
    saltLength = 16,
    hashLength = 32,
    parallelism = 1,
    memoryKB = 4096,
    iterations = 2,
  )

  "PasswordService" when {
    "hashPassword" should {
      "successfully hash a password" in {
        val passwordService = ZIO
          .service[PasswordService]
          .provide(
            PasswordService.live,
            ZLayer.succeed(passwordConfig),
          )
          .zioValue

        val password = arbitrarySample[Password]

        val passwordHashResult = passwordService.hashPassword(password).zioEither

        assert(passwordHashResult.isRight)
        assert(passwordHashResult.value.value.nonEmpty)
      }
    }

    "verifyPassword" should {
      "successfully verify a correct password against its hash" in {
        val passwordService = ZIO
          .service[PasswordService]
          .provide(
            PasswordService.live,
            ZLayer.succeed(passwordConfig),
          )
          .zioValue

        val password = arbitrarySample[Password]

        val passwordHash = passwordService.hashPassword(password).zioValue

        val verifyResult = passwordService.verifyPassword(password, passwordHash).zioEither

        assert(verifyResult.isRight)
        assert(verifyResult.value)
      }

      "successfully fail to verify an incorrect password against a hash" in {
        val passwordService = ZIO
          .service[PasswordService]
          .provide(
            PasswordService.live,
            ZLayer.succeed(passwordConfig),
          )
          .zioValue

        val password1 = arbitrarySample[Password]
        val password2 = arbitrarySample[Password]

        val passwordHash1 = passwordService.hashPassword(password1).zioValue

        val verifyResult = passwordService.verifyPassword(password2, passwordHash1).zioEither

        assert(verifyResult.isRight)
        assert(!verifyResult.value)
      }
    }
  }
}
