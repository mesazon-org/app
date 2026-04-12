package io.mesazon.gateway.auth

import io.jsonwebtoken.{Clock as JJwtClock, JwtException, Jwts}
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.JwtService.*
import io.mesazon.gateway.config.JwtConfig
import io.mesazon.generator.IDGenerator
import zio.*

import java.time.Instant
import java.util.Date

trait JwtService {
  def generateAccessToken(userID: UserID): IO[ServiceError, AccessJwt]

  def generateRefreshToken(userID: UserID): IO[ServiceError, RefreshJwt]

  def verifyAccessToken(accessToken: AccessToken): IO[ServiceError, AuthedUserAccess]

  def verifyRefreshToken(refreshToken: RefreshToken): IO[ServiceError, AuthedUserRefresh]
}

object JwtService {

  type AccessJwt         = (accessToken: AccessToken, expiresIn: Duration)
  type RefreshJwt        = (tokenID: TokenID, refreshToken: RefreshToken, expiresAt: ExpiresAt)
  type AuthedUserRefresh = (tokenID: TokenID, userID: UserID)
  type AuthedUserAccess  = UserID

  private final class JwtServiceImpl(
      jwtConfig: JwtConfig,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends JwtService {

    private val jjwtClock = timeProvider.clock.map(clock =>
      new JJwtClock {
        override def now(): Date = Date.from(Instant.now(clock))
      }
    )

    override def generateAccessToken(userID: UserID): IO[ServiceError, AccessJwt] =
      for {
        instantNow     <- timeProvider.instantNow
        expirationDate <- ZIO
          .attempt(Date.from(instantNow.plusSeconds(jwtConfig.accessTokenExpiresAtOffset.toSeconds)))
          .mapError(error =>
            ServiceError.InternalServerError
              .UnexpectedError("Failed to calculate access token expiration date", Some(error))
          )
        accessTokenRaw <- ZIO
          .attempt(
            Jwts.builder.claims
              .subject(userID.value)
              .issuer(jwtConfig.issuer)
              .expiration(expirationDate)
              .and
              .signWith(jwtConfig.secretKey)
              .compact
          )
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Failed to generate access token", Some(error))
          )
        accessToken <- ZIO
          .attempt(AccessToken.applyUnsafe(accessTokenRaw))
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Failed to apply access token", Some(error))
          )
      } yield (accessToken, jwtConfig.accessTokenExpiresAtOffset)

    override def generateRefreshToken(userID: UserID): IO[ServiceError, RefreshJwt] =
      for {
        instantNow <- timeProvider.instantNow
        tokenIDRaw <- idGenerator.generate
        expiresAt = ExpiresAt(instantNow.plusSeconds(jwtConfig.refreshTokenExpiresAtOffset.toSeconds))
        tokenID <- ZIO
          .attempt(TokenID.applyUnsafe(tokenIDRaw))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to apply TokenID", Some(error)))
        refreshTokenRaw <- ZIO
          .attempt(
            Jwts.builder.claims
              .id(tokenID.value)
              .subject(userID.value)
              .issuer(jwtConfig.issuer)
              .expiration(Date.from(expiresAt.value))
              .and
              .signWith(jwtConfig.secretKey)
              .compact
          )
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Failed to generate refresh token", Some(error))
          )
        refreshToken <- ZIO
          .attempt(RefreshToken.applyUnsafe(refreshTokenRaw))
          .mapError(error =>
            ServiceError.InternalServerError.UnexpectedError("Failed to apply refresh token", Some(error))
          )
      } yield (tokenID, refreshToken, expiresAt)

    override def verifyAccessToken(accessToken: AccessToken): IO[ServiceError, AuthedUserAccess] =
      for {
        clock <- jjwtClock
        jws   <- ZIO
          .attempt(
            Jwts.parser
              .clock(clock)
              .requireIssuer(jwtConfig.issuer)
              .verifyWith(jwtConfig.secretKey)
              .build
              .parseSignedClaims(accessToken.value)
          )
          .mapError {
            case error: JwtException =>
              ServiceError.UnauthorizedError.FailedToVerifyJwt("Failed to parse and verify access token", Some(error))
            case error =>
              ServiceError.InternalServerError.UnexpectedError(
                "Unexpected failed to parse and verify access token",
                Some(error),
              )
          }
        userID <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract subject from access token")
          )(
            Option(jws.getPayload.getSubject)
          )
          .flatMap(userIDRaw =>
            ZIO
              .fromEither(UserID.either(userIDRaw))
              .mapError(error =>
                ServiceError.InternalServerError
                  .UnexpectedError(s"Failed to apply UserID from access token subject: $error")
              )
          )
      } yield userID

    override def verifyRefreshToken(refreshToken: RefreshToken): IO[ServiceError, AuthedUserRefresh] =
      for {
        clock <- jjwtClock
        jws   <- ZIO
          .attempt(
            Jwts.parser
              .clock(clock)
              .requireIssuer(jwtConfig.issuer)
              .verifyWith(jwtConfig.secretKey)
              .build
              .parseSignedClaims(refreshToken.value)
          )
          .mapError {
            case error: JwtException =>
              ServiceError.UnauthorizedError.FailedToVerifyJwt("Failed to parse and verify refresh token", Some(error))
            case error =>
              ServiceError.InternalServerError.UnexpectedError("Unexpected failed to parse refresh token", Some(error))
          }
        userID <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract subject from refresh token")
          )(
            Option(jws.getPayload.getSubject)
          )
          .flatMap(userIDRaw =>
            ZIO
              .fromEither(UserID.either(userIDRaw))
              .mapError(error =>
                ServiceError.InternalServerError
                  .UnexpectedError(s"Failed to apply UserID from refresh token subject: $error")
              )
          )
        tokenID <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract token id from refresh token")
          )(
            Option(jws.getPayload.getId)
          )
          .flatMap(tokenIDRaw =>
            ZIO
              .fromEither(TokenID.either(tokenIDRaw))
              .mapError(error =>
                ServiceError.InternalServerError
                  .UnexpectedError(s"Failed to apply TokenID from refresh token id: $error")
              )
          )
      } yield tokenID -> userID
  }

  private def observed(service: JwtService): JwtService = service

  val live = ZLayer.derive[JwtServiceImpl] >>> ZLayer.fromFunction(observed)
}
