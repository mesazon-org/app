package io.mesazon.gateway.unit.auth

import io.jsonwebtoken.Jwts
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.auth.JwtService
import io.mesazon.gateway.auth.JwtService.AuthedUserRefresh
import io.mesazon.gateway.config.JwtConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Date

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
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessJwtResult = jwtService.generateAccessToken(userID).zioEither

        assert(accessJwtResult.isRight)

        accessJwtResult.value.expiresIn shouldBe jwtConfig.accessTokenExpiresAtOffset

        val jws = Jwts.parser
          .verifyWith(jwtConfig.secretKey)
          .build
          .parseSignedClaims(accessJwtResult.value.accessToken.value)

        jws.getPayload.getSubject shouldBe userID.value
        jws.getPayload.getIssuer shouldBe jwtConfig.issuer
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.accessTokenExpiresAtOffset.toSeconds
        )
      }
    }

    "generateRefreshToken" should {
      "generate a valid JWT token" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshJwtResult = jwtService.generateRefreshToken(userID).zioEither

        assert(refreshJwtResult.isRight)

        val jws = Jwts.parser
          .verifyWith(jwtConfig.secretKey)
          .build
          .parseSignedClaims(refreshJwtResult.value.refreshToken.value)

        jws.getPayload.getId shouldBe "1"
        jws.getPayload.getSubject shouldBe userID.value
        jws.getPayload.getIssuer shouldBe jwtConfig.issuer
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.refreshTokenExpiresAtOffset.toSeconds
        )
      }
    }

    "verifyAccessToken" should {
      "successfully verify a valid JWT token and extract claims" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val verifyResult = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioEither

        assert(verifyResult.isRight)

        verifyResult.value shouldBe userID
      }

      "fail with FailedToVerifyJwt with invalid JWT token" in {
        val clockFixed = Clock.fixed(Instant.now(), ZoneOffset.UTC)
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessTokenInvalid = AccessToken.assume("invalid.access.token")

        val failedToVerifyJwt = jwtService.verifyAccessToken(accessTokenInvalid).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"
      }

      "fail with FailedToVerifyJwt if the token is expired" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.minusSeconds(1)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"
      }

      "fail with FailedToVerifyJwt if the token issuer is invalid" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .issuer("invalid-issuer")
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"
      }

      "fail with FailedToVerifyJwt if the token signature is invalid" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(Jwts.SIG.HS256.key().build()) // Sign with a different key to make the signature invalid
          .compact

        val failedToVerifyJwt = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"
      }
    }

    "verifyRefreshToken" should {
      "successfully verify a refresh token" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val verifyResult: Either[ServiceError, AuthedUserRefresh] =
          jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioEither

        verifyResult.value shouldBe (TokenID.assume("1"), userID)
      }

      "fail with FailedToVerifyJwt if the refresh token is invalid" in {
        val clockFixed = Clock.fixed(Instant.now(), ZoneOffset.UTC)
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshTokenInvalid = RefreshToken.assume("invalid.refresh.token")

        val failedToVerifyJwt = jwtService.verifyRefreshToken(refreshTokenInvalid).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"
      }

      "fail with FailedToVerifyJwt if the refresh token is expired" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.minusSeconds(1)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"
      }

      "fail with FailedToVerifyJwt if the refresh token signature is invalid" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(Jwts.SIG.HS256.key().build()) // Sign with a different key to make the signature invalid
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"
      }

      "fail with FailedToVerifyJwt if the refresh token id is missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        failedToVerifyJwt.message shouldBe "Failed to extract token id from refresh token"
      }

      "fail with FailedToVerifyJwt if refresh token issuer is invalid" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val refreshTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer("invalid-issuer")
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"
      }
    }
  }
}
