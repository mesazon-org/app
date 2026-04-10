package io.mesazon.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.UserTokenRow
import io.mesazon.gateway.repository.queries.UserTokenQueries
import zio.*

trait UserTokenRepository {
  def upsertUserToken(
      tokenID: TokenID,
      userID: UserID,
      tokenType: TokenType,
      expiresAt: ExpiresAt,
      tokenIDOptOld: Option[TokenID] = None,
  ): IO[ServiceError, Unit]

  def getUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): IO[ServiceError, Option[UserTokenRow]]

  def deleteUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): IO[ServiceError, Unit]

  def deleteAllUserTokens(userID: UserID): IO[ServiceError, Unit]
}

object UserTokenRepository {

  private final class UserTokenRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      userTokenQueries: UserTokenQueries,
      timeProvider: TimeProvider,
  ) extends UserTokenRepository {

    override def upsertUserToken(
        tokenID: TokenID,
        userID: UserID,
        tokenType: TokenType,
        expiresAt: ExpiresAt,
        tokenIDOptOld: Option[TokenID] = None,
    ): IO[ServiceError, Unit] =
      for {
        instantNow <- timeProvider.instantNow
        userTokenRowNew = UserTokenRow(
          tokenID,
          userID,
          tokenType,
          CreatedAt(instantNow),
          expiresAt,
        )
        _ <- tokenIDOptOld match {
          case Some(tokenIDOld) =>
            database
              .transactionOrWiden(for {
                _ <- userTokenQueries.deleteUserToken(tokenIDOld, userID, tokenType)
                _ <- database.transactionOrWiden(userTokenQueries.insertUserToken(userTokenRowNew))
              } yield ())
              .mapError(e =>
                ServiceError.InternalServerError
                  .DatabaseError(
                    s"Failed to upsert user token: [$tokenIDOld], [$tokenID], [$tokenType], [$userID], $expiresAt]",
                    e,
                  )
              )
          case None =>
            database
              .transactionOrWiden(userTokenQueries.insertUserToken(userTokenRowNew))
              .mapError(e =>
                ServiceError.InternalServerError
                  .DatabaseError(s"Failed to insert user token: [$tokenID], [$tokenType], [$userID], $expiresAt]", e)
              )
        }
      } yield ()

    override def getUserToken(
        tokenID: TokenID,
        userID: UserID,
        tokenType: TokenType,
    ): IO[ServiceError, Option[UserTokenRow]] =
      database
        .transactionOrWiden(userTokenQueries.getUserToken(tokenID, userID, tokenType))
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(s"Failed to get user token: [$tokenID], [$tokenType], [$userID]", e)
        )

    override def deleteUserToken(tokenID: TokenID, userID: UserID, tokenType: TokenType): IO[ServiceError, Unit] =
      database
        .transactionOrWiden(userTokenQueries.deleteUserToken(tokenID, userID, tokenType))
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(s"Failed to delete user token: [$tokenID], [$tokenType], [$userID]", e)
        )

    override def deleteAllUserTokens(userID: UserID): IO[ServiceError, Unit] =
      database
        .transactionOrWiden(userTokenQueries.deleteAllUserTokensForUser(userID))
        .mapError(e =>
          ServiceError.InternalServerError
            .DatabaseError(s"Failed to delete all user tokens for user: [$userID]", e)
        )
  }

  private def observed(userTokenRepository: UserTokenRepository): UserTokenRepository = userTokenRepository

  val live = ZLayer.derive[UserTokenRepositoryImpl] >>> ZLayer.fromFunction(observed)
}
