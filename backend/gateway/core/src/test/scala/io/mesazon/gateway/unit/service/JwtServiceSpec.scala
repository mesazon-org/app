package io.mesazon.gateway.unit.service

import io.jsonwebtoken.Jwts
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.JwtConfig
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.testkit.base.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Date
import scala.jdk.CollectionConverters.SetHasAsScala
import scala.util.chaining.scalaUtilChainingOps

class JwtServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  val jwtConfig = JwtConfig(
    secretKey = Jwts.SIG.HS256.key().build(),
    issuer = "test-issuer",
    accessTokenExpiresAtOffset = 1.minute,
    refreshTokenExpiresAtOffset = 2.minutes,
    resetPasswordTokenExpiresAtOffset = 1.minute,
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
        jws.getPayload.getAudience.asScala shouldBe Set("auth:refresh")
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.refreshTokenExpiresAtOffset.toSeconds
        )
      }
    }

    "generateResetPasswordToken" should {
      "generate a valid JWT token" in {
        val instantNow = Instant.now.truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val resetPasswordJwtResult = jwtService.generateResetPasswordToken(userID).zioEither

        assert(resetPasswordJwtResult.isRight)

        val jws = Jwts.parser
          .verifyWith(jwtConfig.secretKey)
          .build
          .parseSignedClaims(resetPasswordJwtResult.value.resetPasswordToken.value)

        jws.getPayload.getId shouldBe "1"
        jws.getPayload.getSubject shouldBe userID.value
        jws.getPayload.getIssuer shouldBe jwtConfig.issuer
        jws.getPayload.getAudience.asScala shouldBe Set("auth:reset_password")
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds
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

      "fail with FailedToVerifyJwt if the token issuer is invalid or missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val issuerOpt = arbitrarySample[Option[String]]

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .pipe(builder => issuerOpt.fold(builder)(builder.issuer))
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
          .audience()
          .add("auth:refresh")
          .and()
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
          .audience()
          .add("auth:refresh")
          .and()
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
          .audience()
          .add("auth:refresh")
          .and()
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
          .audience()
          .add("auth:refresh")
          .and()
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        failedToVerifyJwt.message shouldBe "Failed to extract token id from refresh token"
      }

      "fail with FailedToVerifyJwt if refresh token issuer is invalid or missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val issuerOpt = arbitrarySample[Option[String]]

        val refreshTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .pipe(builder => issuerOpt.fold(builder)(builder.issuer))
          .audience()
          .add("auth:refresh")
          .and()
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"
      }

      "fail with FailedToVerifyJwt if refresh token audience is invalid or missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val audienceOpt = arbitrarySample[Option[String]]

        val refreshTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .pipe(builder => audienceOpt.fold(builder)(builder.audience.add(_).and))
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"
      }
    }

    "verifyResetPasswordToken" should {
      "successfully verify a reset password token" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .audience()
          .add("auth:reset_password")
          .and
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val verifyResult: Either[ServiceError, AuthedUserRefresh] =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioEither

        verifyResult.value shouldBe (TokenID.assume("1"), userID)
      }

      "fail with FailedToVerifyJwt if the reset password token is invalid" in {
        val clockFixed = Clock.fixed(Instant.now(), ZoneOffset.UTC)
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val resetPasswordTokenInvalid = ResetPasswordToken.assume("invalid.reset.password.token")

        val failedToVerifyJwt = jwtService.verifyResetPasswordToken(resetPasswordTokenInvalid).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify reset password token"
      }

      "fail with FailedToVerifyJwt if the reset password token is expired" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .audience()
          .add("auth:reset_password")
          .and
          .expiration(Date.from(instantNow.minusSeconds(1)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify reset password token"
      }

      "fail with FailedToVerifyJwt if the reset password token signature is invalid" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .audience()
          .add("auth:reset_password")
          .and
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(Jwts.SIG.HS256.key().build())
          .compact

        val failedToVerifyJwt =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify reset password token"
      }

      "fail with FailedToVerifyJwt if reset password token id is missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val resetPasswordTokenRaw = Jwts.builder.claims
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .audience()
          .add("auth:reset_password")
          .and
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        failedToVerifyJwt.message shouldBe "Failed to extract token id from reset password token"
      }

      "fail with FailedToVerifyJwt if reset password token issuer is invalid or missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val issuerOpt = arbitrarySample[Option[String]]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .pipe(builder => issuerOpt.fold(builder)(builder.issuer))
          .audience()
          .add("auth:reset_password")
          .and
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify reset password token"
      }

      "fail with FailedToVerifyJwt if reset password token audience is invalid or missing" in {
        val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clockFixed = Clock.fixed(instantNow, ZoneOffset.UTC)
        val userID     = arbitrarySample[UserID]
        val jwtService = ZIO
          .service[JwtService]
          .provide(JwtService.live, jwtConfigLive, Mocks.timeProviderLive(clockFixed), Mocks.idGeneratorLive)
          .zioValue

        val audienceOpt = arbitrarySample[Option[String]]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id("1")
          .subject(userID.value)
          .issuer(jwtConfig.issuer)
          .pipe(builder => audienceOpt.fold(builder)(builder.audience.add(_).and))
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify reset password token"
      }
    }
  }
}
