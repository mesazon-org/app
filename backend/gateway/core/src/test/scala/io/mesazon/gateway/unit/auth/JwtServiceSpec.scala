package io.mesazon.gateway.unit.auth

import io.jsonwebtoken.Jwts
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.auth.JwtService
import io.mesazon.gateway.config.JwtConfig
import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.*
import zio.*

import java.time.{Clock, Instant, ZoneOffset}

class JwtServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  val jwtConfig = JwtConfig(
    secretKey = Jwts.SIG.HS256.key().build(),
    issuer = "test-issuer",
    accessTokenExpiresAtOffset = 1.minute,
    refreshTokenExpiresAtOffset = 2.minutes,
  )

  val jwtConfigLive = ZLayer.succeed(jwtConfig)

  "JwtService" when {
    "generateAccessToken" should {
      "generate a valid JWT token" in {
        val instantNow   = Instant.now()
        val clockFixed   = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID       = arbitrarySample[UserID]
        val onboardStage = arbitrarySample[OnboardStage]
        val jwtService   = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessJwtResult = jwtService.generateAccessToken(userID, onboardStage).zioEither

        assert(accessJwtResult.isRight)

        accessJwtResult.value.expiresIn shouldBe jwtConfig.accessTokenExpiresAtOffset
      }
    }
  }
}
