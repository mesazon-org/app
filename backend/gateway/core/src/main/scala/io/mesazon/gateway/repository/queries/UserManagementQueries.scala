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

  private val frSchema           = Fragment.const(config.schema)
  private val frUserOnboardTable = Fragment.const(config.userOnboardTable)
  private val frUserDetailsTable = Fragment.const(config.userDetailsTable)

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
    fr"""user_id,
        |email,
        |full_name,
        |password_hash,
        |phone_number,
        |stage,
        |created_at,
        |updated_at
     """.stripMargin

  def insertUserOnboard(userOnboardRow: UserOnboardRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frUserOnboardTable (
           |  $userOnboardFields
           |)
           |VALUES ($userOnboardRow)
           |""".stripMargin.update.run.map(_ => ())
    }

  def updateOnboardUser(
      userID: UserID,
      fullName: Option[FullName],
      phoneNumber: Option[PhoneNumberE164],
      passwordHash: Option[PasswordHash],
      stage: OnboardStage,
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
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
           |""".stripMargin.update.run.map(_ => ())
    }

  def getOnboardUser(
      userID: UserID
  ): TranzactIO[Option[UserOnboardRow]] =
    tzio {
      sql"""
           |SELECT $userOnboardFields
           |FROM $frSchema.$frUserOnboardTable
           |WHERE user_id = $userID
           |""".stripMargin.query[UserOnboardRow].option
    }

  def getOnboardUserByEmail(
      email: Email
  ): TranzactIO[Option[UserOnboardRow]] =
    tzio {
      sql"""
           |SELECT $userOnboardFields
           |FROM $frSchema.$frUserOnboardTable
           |WHERE email = $email
           |""".stripMargin.query[UserOnboardRow].option
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
