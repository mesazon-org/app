package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.domain.*
import zio.*

final class UserManagementQueries(
    config: RepositoryConfig
) {

  private val frSchema                = Fragment.const(config.schema)
  private val frUserOnboardTable      = Fragment.const(config.userOnboardTable)
  private val frUserDetailsTable      = Fragment.const(config.userDetailsTable)
  private val frUserOtpTable          = Fragment.const(config.userOtpTable)
  private val frUserRefreshTokenTable = Fragment.const(config.userRefreshTokenTable)

  case class UpdateUserDetailsQuery(
      userID: UserID,
      firstName: Option[FirstName],
      lastName: Option[LastName],
      phoneNumber: Option[PhoneNumberE164],
      addressLine1: Option[AddressLine1],
      addressLine2: Option[AddressLine2],
      city: Option[City],
      postalCode: Option[PostalCode],
      company: Option[Company],
      updatedAt: UpdatedAt,
  )

  val userOnboardFields =
    fr"""
        |user_id,
        |email,
        |full_name,
        |password_hash,
        |phone_number,
        |stage,
        |created_at,
        |updated_at
     """.stripMargin

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

  val userRefreshTokenFields =
    fr"""
        |token_id,
        |user_id,
        |created_at,
        |expires_at
     """.stripMargin

  def insertUserOnboardEmail(userOnboardRow: UserOnboardRow): TranzactIO[UserOnboardRow] =
    tzio {
      val condUserId = fr"user_id = ${userOnboardRow.userID}"
      val condEmail  = fr"email = ${userOnboardRow.email}"

      val conflictCondition = Fragments.or(condUserId, condEmail)

      sql"""
           |WITH inserted AS (
           |  INSERT INTO $frSchema.$frUserOnboardTable ($userOnboardFields)
           |  VALUES ($userOnboardRow)
           |  ON CONFLICT (email) DO NOTHING
           |  RETURNING $userOnboardFields
           |)
           |
           |SELECT * FROM inserted
           |UNION ALL
           |SELECT $userOnboardFields FROM $frSchema.$frUserOnboardTable
           |WHERE $conflictCondition
           |LIMIT 1;
           |""".stripMargin.query[UserOnboardRow].unique
    }

  def upsertUserOtp(userOtpRow: UserOtpRow): TranzactIO[UserOtpRow] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frUserOtpTable ($userOtpFields)
           |VALUES ($userOtpRow)
           |ON CONFLICT (user_id, otp_type) DO UPDATE SET
           |  otp_id = EXCLUDED.otp_id,
           |  otp = EXCLUDED.otp,
           |  expires_at = EXCLUDED.expires_at,
           |  updated_at = EXCLUDED.updated_at
           |RETURNING $userOtpFields
           |""".stripMargin.query[UserOtpRow].unique
    }

  def updateUserOtp(otpID: OtpID, expiresAt: ExpiresAt, updatedAt: UpdatedAt): TranzactIO[UserOtpRow] =
    tzio {
      sql"""
           |UPDATE $frSchema.$frUserOtpTable
           |SET
           |  expires_at = $expiresAt,
           |  updated_at = $updatedAt
           |WHERE otp_id = $otpID
           |RETURNING $userOtpFields
           |""".stripMargin.query[UserOtpRow].unique
    }

  def deleteUserOtp(otpID: OtpID): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserOtpTable
           |WHERE otp_id = $otpID
           |""".stripMargin.update.run.map(_ => ())
    }

  def getUserOtp(optID: OtpID): TranzactIO[Option[UserOtpRow]] =
    tzio {
      sql"""
           |SELECT $userOtpFields
           |FROM $frSchema.$frUserOtpTable
           |WHERE otp_id = $optID
           |""".stripMargin.query[UserOtpRow].option
    }

  def getUserOtpByUserID(userID: UserID, otpType: OtpType): TranzactIO[Option[UserOtpRow]] =
    tzio {
      sql"""
           |SELECT $userOtpFields
           |FROM $frSchema.$frUserOtpTable
           |WHERE user_id = $userID AND otp_type = $otpType
           |""".stripMargin.query[UserOtpRow].option
    }

  def upsertUserRefreshToken(
      userRefreshTokenRow: UserRefreshTokenRow,
      maybeOldTokenID: Option[TokenID],
  ): TranzactIO[Unit] = {
    val insertQuery =
      sql"""
           |INSERT INTO $frSchema.$frUserRefreshTokenTable ($userRefreshTokenFields)
           |VALUES ($userRefreshTokenRow)
         """.stripMargin

    maybeOldTokenID match {
      case Some(oldTokenID) =>
        val deleteQuery =
          sql"""
               |DELETE FROM $frSchema.$frUserRefreshTokenTable
               |WHERE token_id = $oldTokenID
           """.stripMargin
        tzio(for {
          _ <- deleteQuery.update.run
          _ <- insertQuery.update.run
        } yield ()).unit
      case None =>
        tzio(insertQuery.update.run).unit
    }
  }

  def getUserRefreshToken(tokenID: TokenID): TranzactIO[Option[UserRefreshTokenRow]] =
    tzio {
      sql"""
           |SELECT $userRefreshTokenFields
           |FROM $frSchema.$frUserRefreshTokenTable
           |WHERE token_id = $tokenID
           |""".stripMargin.query[UserRefreshTokenRow].option
    }

  def getAllUserRefreshTokens(userID: UserID): TranzactIO[List[UserRefreshTokenRow]] =
    tzio {
      sql"""
           |SELECT $userRefreshTokenFields
           |FROM $frSchema.$frUserRefreshTokenTable
           |WHERE user_id = $userID
           |""".stripMargin.query[UserRefreshTokenRow].to[List]
    }

  def deleteUserRefreshToken(tokenID: TokenID): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserRefreshTokenTable
           |WHERE token_id = $tokenID
           |""".stripMargin.update.run
    }.unit

  def deleteAllUserRefreshTokens(userID: UserID): TranzactIO[Unit] =
    tzio {
      sql"""
           |DELETE FROM $frSchema.$frUserRefreshTokenTable
           |WHERE user_id = $userID
           |""".stripMargin.update.run
    }.unit

  def updateUserOnboard(
      userID: UserID,
      fullName: Option[FullName],
      phoneNumber: Option[PhoneNumberE164],
      passwordHash: Option[PasswordHash],
      stage: OnboardStage,
      updatedAt: UpdatedAt,
  ): TranzactIO[UserOnboardRow] =
    tzio {
      sql"""
           |UPDATE $frSchema.$frUserOnboardTable
           |SET
           |  full_name = COALESCE($fullName, $frUserOnboardTable.full_name),
           |  phone_number = COALESCE($phoneNumber, $frUserOnboardTable.phone_number),
           |  password_hash = COALESCE($passwordHash, $frUserOnboardTable.password_hash),
           |  stage = $stage,
           |  updated_at = $updatedAt
           |WHERE user_id = $userID
           |RETURNING $userOnboardFields
           |""".stripMargin.query[UserOnboardRow].unique
    }

  def getUserOnboard(
      userID: UserID
  ): TranzactIO[Option[UserOnboardRow]] =
    tzio {
      sql"""
           |SELECT $userOnboardFields
           |FROM $frSchema.$frUserOnboardTable
           |WHERE user_id = $userID
           |""".stripMargin.query[UserOnboardRow].option
    }

  def getUserOnboardByEmail(
      email: Email
  ): TranzactIO[Option[UserOnboardRow]] =
    tzio {
      sql"""
           |SELECT $userOnboardFields
           |FROM $frSchema.$frUserOnboardTable
           |WHERE email = $email
           |""".stripMargin.query[UserOnboardRow].option
    }

  def getAllUserOnboardRows: TranzactIO[List[UserOnboardRow]] =
    tzio {
      sql"""
           |SELECT $userOnboardFields
           |FROM $frSchema.$frUserOnboardTable
           |""".stripMargin.query[UserOnboardRow].to[List]
    }

  def getAllUserOtpRows: TranzactIO[List[UserOtpRow]] =
    tzio {
      sql"""
             |SELECT $userOtpFields
             |FROM $frSchema.$frUserOtpTable
             |""".stripMargin.query[UserOtpRow].to[List]
    }

  def insertUserDetailsQuery(userDetailsRow: UserDetailsRow): TranzactIO[Unit] =
    tzio {
      sql"""INSERT INTO $frSchema.$frUserDetailsTable (
          user_id,
          email,
          first_name,
          last_name,
          phone_number,
          address_line_1,
          address_line_2,
          city,
          postal_code,
          company,
          created_at,
          updated_at
        ) VALUES (
          ${userDetailsRow.userID},
          ${userDetailsRow.email},
          ${userDetailsRow.firstName},
          ${userDetailsRow.lastName},
          ${userDetailsRow.phoneNumber},
          ${userDetailsRow.addressLine1},
          ${userDetailsRow.addressLine2},
          ${userDetailsRow.city},
          ${userDetailsRow.postalCode},
          ${userDetailsRow.company},
          ${userDetailsRow.createdAt},
          ${userDetailsRow.updatedAt}
        )""".update.run.map(_ => ())
    }

  def getUserDetailsQuery(userID: UserID): TranzactIO[Option[UserDetailsRow]] =
    tzio {
      sql"""SELECT
              user_id,
              email,
              first_name,
              last_name,
              phone_number,
              address_line_1,
              address_line_2,
              city,
              postal_code,
              company,
              created_at,
              updated_at
              FROM
              $frSchema.$frUserDetailsTable
              WHERE user_id = $userID""".query[UserDetailsRow].option
    }

  def updateUserDetailsQuery(query: UpdateUserDetailsQuery): TranzactIO[Unit] =
    tzio {
      sql"""
      UPDATE $frSchema.$frUserDetailsTable
      SET
        first_name            = COALESCE(${query.firstName}, first_name),
        last_name             = COALESCE(${query.lastName}, last_name),
        phone_number          = COALESCE(${query.phoneNumber}, phone_number),
        address_line_1        = COALESCE(${query.addressLine1}, address_line_1),
        address_line_2        = COALESCE(${query.addressLine2}, address_line_2),
        city                  = COALESCE(${query.city}, city),
        postal_code           = COALESCE(${query.postalCode}, postal_code),
        company               = COALESCE(${query.company}, company),
        updated_at            = ${query.updatedAt}
      WHERE
        user_id = ${query.userID}
    """.update.run.map(_ => ())
    }
}

object UserManagementQueries {

  val live = ZLayer.derive[UserManagementQueries]
}
