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
  def generateAccessToken(userID: UserID, onboardStage: OnboardStage): IO[ServiceError, AccessJwt]

  def generateRefreshToken(userID: UserID, onboardStage: OnboardStage): IO[ServiceError, RefreshJwt]

  def verifyAccessToken(jwt: Jwt): IO[ServiceError, AuthedUserAccess]

  def verifyRefreshToken(jwt: Jwt): IO[ServiceError, AuthedUserRefresh]
}

object JwtService {

  type AccessJwt         = (jwt: Jwt, expiresIn: Duration)
  type RefreshJwt        = (jwtID: JwtID, jwt: Jwt)
  type AuthedUserRefresh = (jwtID: JwtID, userID: UserID)
  type AuthedUserAccess  = (userID: UserID, onboardStage: OnboardStage)

  inline private val onboardStageClaimKey = "onboardStage"

  private final class JwtServiceImpl(
      config: JwtConfig,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends JwtService {

    private val jjwtClock = timeProvider.clock.map(clock =>
      new JJwtClock {
        override def now(): Date = Date.from(Instant.now(clock))
      }
    )

    override def generateAccessToken(userID: UserID, onboardStage: OnboardStage): IO[ServiceError, AccessJwt] =
      for {
        instantNow     <- timeProvider.instantNow
        expirationDate <- ZIO
          .attempt(Date.from(instantNow.plusSeconds(config.accessTokenExpiresAtOffset.toSeconds)))
          .mapError(error =>
            ServiceError.InternalServerError
              .UnexpectedError("Failed to calculate access token expiration date", Some(error))
          )
        jwtRaw <- ZIO
          .attempt(
            Jwts.builder.claims
              .subject(userID.value)
              .issuer(config.issuer)
              .expiration(expirationDate)
              .add(onboardStageClaimKey, onboardStage.toString)
              .and
              .signWith(config.secretKey)
              .compact
          )
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to generate Jwt", Some(error)))
        jwt <- ZIO
          .attempt(Jwt.applyUnsafe(jwtRaw))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to apply Jwt", Some(error)))
      } yield jwt -> config.accessTokenExpiresAtOffset

    override def generateRefreshToken(userID: UserID, onboardStage: OnboardStage): IO[ServiceError, RefreshJwt] =
      for {
        instantNow <- timeProvider.instantNow
        jwtIDRaw   <- idGenerator.generate
        jwtID      <- ZIO
          .attempt(JwtID.applyUnsafe(jwtIDRaw))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to apply JwtID", Some(error)))
        jwtRaw <- ZIO
          .attempt(
            Jwts.builder.claims
              .id(jwtID.value)
              .subject(userID.value)
              .issuer(config.issuer)
              .expiration(Date.from(instantNow.plusSeconds(config.refreshTokenExpiresAtOffset.toSeconds)))
              .and
              .signWith(config.secretKey)
              .compact
          )
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to generate Jwt", Some(error)))
        jwt <- ZIO
          .attempt(Jwt.applyUnsafe(jwtRaw))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to apply Jwt", Some(error)))
      } yield jwtID -> jwt

    override def verifyAccessToken(jwt: Jwt): IO[ServiceError, AuthedUserAccess] =
      for {
        clock <- jjwtClock
        jws   <- ZIO
          .attempt(
            Jwts.parser
              .clock(clock)
              .requireIssuer(config.issuer)
              .verifyWith(config.secretKey)
              .build
              .parseSignedClaims(jwt.value)
          )
          .mapError {
            case error: JwtException =>
              ServiceError.InternalServerError.UnexpectedError("Failed to parse and verify access Jwt", Some(error))
            case error => ServiceError.InternalServerError.UnexpectedError("Failed to parse access Jwt", Some(error))
          }
        userID <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract subject from access jwt")
          )(
            Option(jws.getPayload.getSubject)
          )
          .flatMap(userIDRaw =>
            ZIO
              .fromEither(UserID.either(userIDRaw))
              .mapError(error =>
                ServiceError.InternalServerError
                  .UnexpectedError(s"Failed to apply UserID from access jwt subject: $error")
              )
          )
        onboardStage <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract onboard stage from access jwt")
          )(
            Option(jws.getPayload.get(onboardStageClaimKey))
          )
          .flatMap(onboardStageRaw =>
            ZIO
              .attempt(OnboardStage.valueOf(onboardStageRaw.toString))
              .mapError(error =>
                ServiceError.InternalServerError
                  .UnexpectedError("Failed to apply OnboardStage from access jwt claim", Some(error))
              )
          )
      } yield userID -> onboardStage

    override def verifyRefreshToken(jwt: Jwt): IO[ServiceError, AuthedUserRefresh] =
      for {
        clock <- jjwtClock
        jws   <- ZIO
          .attempt(
            Jwts.parser
              .clock(clock)
              .requireIssuer(config.issuer)
              .verifyWith(config.secretKey)
              .build
              .parseSignedClaims(jwt.value)
          )
          .mapError {
            case error: JwtException =>
              ServiceError.InternalServerError.UnexpectedError("Failed to parse and verify refresh Jwt", Some(error))
            case error => ServiceError.InternalServerError.UnexpectedError("Failed to parse refresh Jwt", Some(error))
          }
        userID <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract subject from refresh jwt")
          )(
            Option(jws.getPayload.getSubject)
          )
          .flatMap(userIDRaw =>
            ZIO
              .fromEither(UserID.either(userIDRaw))
              .mapError(error =>
                ServiceError.InternalServerError
                  .UnexpectedError(s"Failed to apply UserID from refresh jwt subject: $error")
              )
          )
        jwtID <- ZIO
          .getOrFailWith(
            ServiceError.InternalServerError.UnexpectedError("Failed to extract jwt id from refresh jwt")
          )(
            Option(jws.getPayload.getId)
          )
          .flatMap(jwtIDRaw =>
            ZIO
              .fromEither(JwtID.either(jwtIDRaw))
              .mapError(error =>
                ServiceError.InternalServerError.UnexpectedError(s"Failed to apply JwtID from refresh jwt id: $error")
              )
          )
      } yield jwtID -> userID
  }

  private def observed(service: JwtService): JwtService = service

  val live = ZLayer.derive[JwtServiceImpl] >>> ZLayer.fromFunction(observed)
}
