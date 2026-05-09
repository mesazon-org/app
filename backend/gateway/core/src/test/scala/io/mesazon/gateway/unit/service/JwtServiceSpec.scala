package io.mesazon.gateway.unit.service

import io.jsonwebtoken.Jwts
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.JwtConfig
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.testkit.base.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock as JavaClock, *}
import java.util.{Date, UUID}
import scala.util.chaining.scalaUtilChainingOps

class JwtServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  "JwtService" when {
    "generateAccessToken" should {
      "generate a valid JWT token" in new TestContext {
        val userID = arbitrarySample[UserID]

        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val accessJwtResult = jwtService.generateAccessToken(userID).zioEither

        assert(accessJwtResult.isRight)

        accessJwtResult.value.expiresIn shouldBe jwtConfig.accessTokenExpiresAtOffset

        val jws = Jwts.parser
          .verifyWith(jwtConfig.secretKey)
          .build
          .parseSignedClaims(accessJwtResult.value.accessToken.value)

        jws.getPayload.getSubject shouldBe userID.value.toString
        jws.getPayload.getIssuer shouldBe jwtConfig.issuer
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.accessTokenExpiresAtOffset.toSeconds
        )

        checkTimeProvider(expectedInstantNowCalls = 1)
        checkIDGenerator()
      }
    }

    "generateRefreshToken" should {
      "generate a valid JWT token" in new TestContext {
        val tokenID = arbitrarySample[TokenID]

        val jwtService = buildJwtService(
          idGeneratorID = Some(tokenID.value),
          clock = clockFixed,
        )

        val userID = arbitrarySample[UserID]

        val refreshJwtResult = jwtService.generateRefreshToken(userID).zioEither

        assert(refreshJwtResult.isRight)

        val jws = Jwts.parser
          .verifyWith(jwtConfig.secretKey)
          .build
          .parseSignedClaims(refreshJwtResult.value.refreshToken.value)

        jws.getPayload.getId shouldBe tokenID.value.toString
        jws.getPayload.getSubject shouldBe userID.value.toString
        jws.getPayload.getIssuer shouldBe jwtConfig.issuer
        jws.getPayload.getAudience should contain theSameElementsAs Set("auth:refresh")
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.refreshTokenExpiresAtOffset.toSeconds
        )

        checkIDGenerator(expectedGenerateCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
      }
    }

    "generateResetPasswordToken" should {
      "generate a valid JWT token" in new TestContext {
        val tokenID = arbitrarySample[TokenID]

        val jwtService = buildJwtService(
          idGeneratorID = Some(tokenID.value),
          clock = clockFixed,
        )

        val userID = arbitrarySample[UserID]

        val resetPasswordJwt = jwtService.generateResetPasswordToken(userID).zioEither

        assert(resetPasswordJwt.isRight)

        resetPasswordJwt.value.expiresIn shouldBe jwtConfig.resetPasswordTokenExpiresAtOffset

        val jws = Jwts.parser
          .verifyWith(jwtConfig.secretKey)
          .build
          .parseSignedClaims(resetPasswordJwt.value.resetPasswordToken.value)

        jws.getPayload.getId shouldBe tokenID.value.toString
        jws.getPayload.getSubject shouldBe userID.value.toString
        jws.getPayload.getIssuer shouldBe jwtConfig.issuer
        jws.getPayload.getAudience should contain theSameElementsAs Set("auth:reset_password")
        jws.getPayload.getExpiration.toInstant shouldBe instantNow.plusSeconds(
          jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds
        )

        checkIDGenerator(expectedGenerateCalls = 1)
        checkTimeProvider(expectedInstantNowCalls = 1)
      }
    }

    "verifyAccessToken" should {
      "successfully verify a valid JWT token and extract claims" in new TestContext {
        val tokenID = arbitrarySample[TokenID]

        val jwtService = buildJwtService(
          idGeneratorID = Some(tokenID.value),
          clock = clockFixed,
        )

        val userID = arbitrarySample[UserID]

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value.toString)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val verifyResult = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioEither

        assert(verifyResult.isRight)

        verifyResult.value shouldBe userID

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt with invalid JWT token" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val accessTokenInvalid = AccessToken.assume("invalid.access.token")

        val failedToVerifyJwt = jwtService.verifyAccessToken(accessTokenInvalid).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the token is expired" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID = arbitrarySample[UserID]

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value.toString)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.minusSeconds(1)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the token issuer is invalid or missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID = arbitrarySample[UserID]

        val issuerOpt = arbitrarySample[Option[String]]

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value.toString)
          .pipe(builder => issuerOpt.fold(builder)(builder.issuer))
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the token signature is invalid" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID = arbitrarySample[UserID]

        val accessTokenRaw = Jwts.builder.claims
          .subject(userID.value.toString)
          .issuer(jwtConfig.issuer)
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(Jwts.SIG.HS256.key().build()) // Sign with a different key to make the signature invalid
          .compact

        val failedToVerifyJwt = jwtService.verifyAccessToken(AccessToken.assume(accessTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify access token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }
    }

    "verifyRefreshToken" should {
      "successfully verify a refresh token" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val refreshTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        verifyResult.value shouldBe (tokenID, userID)

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the refresh token is invalid" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val refreshTokenInvalid = RefreshToken.assume("invalid.refresh.token")

        val failedToVerifyJwt = jwtService.verifyRefreshToken(refreshTokenInvalid).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the refresh token is expired" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val refreshTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the refresh token signature is invalid" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val refreshTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the refresh token id is missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID = arbitrarySample[UserID]

        val refreshTokenRaw = Jwts.builder.claims
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if refresh token issuer is invalid or missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val issuerOpt = arbitrarySample[Option[String]]

        val refreshTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if refresh token audience is invalid or missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val audienceOpt = arbitrarySample[Option[String]]

        val refreshTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
          .issuer(jwtConfig.issuer)
          .pipe(builder => audienceOpt.fold(builder)(builder.audience.add(_).and))
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val failedToVerifyJwt = jwtService.verifyRefreshToken(RefreshToken.assume(refreshTokenRaw)).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify refresh token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }
    }

    "verifyResetPasswordToken" should {
      "successfully verify a reset password token" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
          .issuer(jwtConfig.issuer)
          .audience()
          .add("auth:reset_password")
          .and
          .expiration(Date.from(instantNow.plusSeconds(jwtConfig.resetPasswordTokenExpiresAtOffset.toSeconds)))
          .and
          .signWith(jwtConfig.secretKey)
          .compact

        val verifyResult =
          jwtService.verifyResetPasswordToken(ResetPasswordToken.assume(resetPasswordTokenRaw)).zioEither

        verifyResult.value shouldBe (tokenID, userID)

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the reset password token is invalid" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val resetPasswordTokenInvalid = ResetPasswordToken.assume("invalid.reset.password.token")

        val failedToVerifyJwt = jwtService.verifyResetPasswordToken(resetPasswordTokenInvalid).zioError

        failedToVerifyJwt shouldBe a[ServiceError.UnauthorizedError.FailedToVerifyJwt]
        failedToVerifyJwt.message shouldBe "Failed to parse and verify reset password token"

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the reset password token is expired" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if the reset password token signature is invalid" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if reset password token id is missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID = arbitrarySample[UserID]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if reset password token issuer is invalid or missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val issuerOpt = arbitrarySample[Option[String]]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
          .pipe(builder => issuerOpt.fold(builder)(builder.issuer))
          .audience
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }

      "fail with FailedToVerifyJwt if reset password token audience is invalid or missing" in new TestContext {
        val jwtService = buildJwtService(
          clock = clockFixed
        )

        val userID  = arbitrarySample[UserID]
        val tokenID = arbitrarySample[TokenID]

        val audienceOpt = arbitrarySample[Option[String]]

        val resetPasswordTokenRaw = Jwts.builder.claims
          .id(tokenID.value.toString)
          .subject(userID.value.toString)
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

        checkTimeProvider(expectedClockCalls = 1)
        checkIDGenerator()
      }
    }
  }

  trait TestContext extends IDGeneratorMock, TimeProviderMock {
    val instantNow = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val clockFixed = JavaClock.fixed(instantNow, ZoneOffset.UTC)

    val jwtConfig = JwtConfig(
      secretKey = Jwts.SIG.HS256.key().build(),
      issuer = "test-issuer",
      accessTokenExpiresAtOffset = 1.minute,
      refreshTokenExpiresAtOffset = 2.minutes,
      resetPasswordTokenExpiresAtOffset = 1.minute,
    )

    def buildJwtService(
        idGeneratorID: Option[UUID] = None,
        clock: JavaClock = JavaClock.systemUTC(),
    ): JwtService = ZIO
      .service[JwtService]
      .provide(
        JwtService.live,
        ZLayer.succeed(jwtConfig),
        timeProviderMockLive(clock),
        idGeneratorMockLive(idGeneratorID),
      )
      .zioValue
  }
}
