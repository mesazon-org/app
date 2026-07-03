package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.domain.*
import zio.*

final class UserOtpQueries(
    config: RepositoryConfig
) {

  private val frSchema       = Fragment.const(config.schema)
  private val frUserOtpTable = Fragment.const(config.userOtpTable)
  private val frTable        = frSchema ++ fr0"." ++ frUserOtpTable

  val frUserOtpFields =
    fr"""
        |otp_id,
        |user_id,
        |otp,
        |otp_type,
        |created_at,
        |updated_at,
        |expires_at
         """.stripMargin

  def insertUserOtp(userOtpRow: UserOtpRow): TranzactIO[Unit] =
    tzio {
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frUserOtpFields ++ fr")" ++
          fr"VALUES (" ++ fr"$userOtpRow" ++ fr")"

      q.update.run.void
    }

  def updateUserOtp(
      otpID: OtpID,
      userID: UserID,
      otpType: OtpType,
      expiresAt: ExpiresAt,
      updatedAt: UpdatedAt,
  ): TranzactIO[UserOtpRow] =
    tzio {
      val q =
        fr"UPDATE" ++ frTable ++
          set(
            NonEmptyList.of(
              fr"expires_at = $expiresAt",
              fr"updated_at = $updatedAt",
            )
          ) ++
          whereAnd(
            fr"otp_id = $otpID",
            fr"user_id = $userID",
            fr"otp_type = $otpType",
          ) ++
          fr"RETURNING" ++ frUserOtpFields

      q.query[UserOtpRow].unique
    }

  def getUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): TranzactIO[Option[UserOtpRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserOtpFields ++
          fr"FROM" ++ frTable ++
          whereAnd(
            fr"otp_id = $otpID",
            fr"user_id = $userID",
            fr"otp_type = $otpType",
          )

      q.query[UserOtpRow].option
    }

  def getUserOtpByOtpID(otpID: OtpID, otpType: OtpType): TranzactIO[Option[UserOtpRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserOtpFields ++
          fr"FROM" ++ frTable ++
          whereAnd(
            fr"otp_id = $otpID",
            fr"otp_type = $otpType",
          )

      q.query[UserOtpRow].option
    }

  def getUserOtpByUserID(userID: UserID, otpType: OtpType): TranzactIO[Option[UserOtpRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserOtpFields ++
          fr"FROM" ++ frTable ++
          whereAnd(
            fr"user_id = $userID",
            fr"otp_type = $otpType",
          )

      q.query[UserOtpRow].option
    }

  def deleteUserOtp(userID: UserID, otpType: OtpType): TranzactIO[Unit] =
    tzio {
      val q =
        fr"DELETE FROM" ++ frTable ++
          whereAnd(
            fr"user_id = $userID",
            fr"otp_type = $otpType",
          )

      q.update.run.void
    }

  def deleteUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): TranzactIO[Unit] =
    tzio {
      val q =
        fr"DELETE FROM" ++ frTable ++
          whereAnd(
            fr"otp_id = $otpID",
            fr"user_id = $userID",
            fr"otp_type = $otpType",
          )

      q.update.run.void
    }

  // Testing
  def getAllUserOtpsTesting: TranzactIO[List[UserOtpRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserOtpFields ++
          fr"FROM" ++ frTable

      q.query[UserOtpRow].to[List]
    }
}

object UserOtpQueries {

  val live = ZLayer.derive[UserOtpQueries]
}
