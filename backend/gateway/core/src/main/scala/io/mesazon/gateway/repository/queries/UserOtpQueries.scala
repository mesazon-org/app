package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
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

  val userOtpFields =
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
      sql"""
           |INSERT INTO $frSchema.$frUserOtpTable ($userOtpFields)
           |VALUES ($userOtpRow)
           |ON CONFLICT DO NOTHING
           |""".stripMargin.update.run
    }.unit

  def updateUserOtp(
      otpID: OtpID,
      userID: UserID,
      otpType: OtpType,
      expiresAt: ExpiresAt,
      updatedAt: UpdatedAt,
  ): TranzactIO[UserOtpRow] =
    tzio {
      sql"""
           |UPDATE $frSchema.$frUserOtpTable
           |SET
           |  expires_at = $expiresAt,
           |  updated_at = $updatedAt
           |WHERE otp_id = $otpID AND user_id = $userID AND otp_type = $otpType
           |RETURNING $userOtpFields
           |""".stripMargin.query[UserOtpRow].unique
    }

  def getUserOtpByUserID(userID: UserID, otpType: OtpType): TranzactIO[Option[UserOtpRow]] =
    tzio {
      sql"""
             |SELECT $userOtpFields
             |FROM $frSchema.$frUserOtpTable
             |WHERE user_id = $userID AND otp_type = $otpType
             |""".stripMargin.query[UserOtpRow].option
    }

  def deleteUserOtp(userID: UserID, otpType: OtpType): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserOtpTable
           |WHERE user_id = $userID AND otp_type = $otpType
           |""".stripMargin.update.run
    }.unit

  def deleteUserOtp(otpID: OtpID, userID: UserID, otpType: OtpType): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserOtpTable
           |WHERE otp_id = $otpID AND user_id = $userID AND otp_type = $otpType
           |""".stripMargin.update.run
    }.unit

  // Testing
  def getAllUserOtps: TranzactIO[List[UserOtpRow]] =
    tzio {
      sql"""
           |SELECT $userOtpFields
           |FROM $frSchema.$frUserOtpTable
           |""".stripMargin.query[UserOtpRow].to[List]
    }
}

object UserOtpQueries {

  val live = ZLayer.derive[UserOtpQueries]
}
