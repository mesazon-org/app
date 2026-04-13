package io.mesazon.gateway.auth

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.PasswordConfig
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import zio.*

trait PasswordService {
  def hashPassword(password: Password): IO[ServiceError, PasswordHash]
  def verifyPassword(password: Password, passwordHash: PasswordHash): IO[ServiceError, Boolean]
}

object PasswordService {

  private final class PasswordServiceArgon2(
      passwordConfig: PasswordConfig
  ) extends PasswordService {

    private val argon2PasswordEncoder =
      new Argon2PasswordEncoder(
        passwordConfig.saltLength,
        passwordConfig.hashLength,
        passwordConfig.parallelism,
        passwordConfig.memoryKB,
        passwordConfig.iterations,
      )

    override def hashPassword(password: Password): IO[ServiceError, PasswordHash] =
      for {
        passwordHashRaw <- ZIO
          .attemptBlocking(argon2PasswordEncoder.encode(password.value))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to hash password", Some(error)))
        passwordHash <- ZIO
          .fromEither(PasswordHash.either(passwordHashRaw))
          .mapError(_ => ServiceError.InternalServerError.UnexpectedError("Failed construct PasswordHash"))
      } yield passwordHash

    override def verifyPassword(password: Password, passwordHash: PasswordHash): IO[ServiceError, Boolean] =
      ZIO
        .attemptBlocking(argon2PasswordEncoder.matches(password.value, passwordHash.value))
        .mapError(error => ServiceError.InternalServerError.UnexpectedError("Failed to verify password", Some(error)))
  }

  private def observed(passwordService: PasswordService): PasswordService = passwordService

  val live = ZLayer.derive[PasswordServiceArgon2] >>> ZLayer.fromFunction(observed)
}
