package io.mesazon.gateway.repository.queries

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

class UserDetailsQueries(config: RepositoryConfig) {

  private val frSchema           = Fragment.const(config.schema)
  private val frUserDetailsTable = Fragment.const(config.userDetailsTable)

  val userDetailsFields =
    fr"""
        |user_id,
        |email,
        |full_name,
        |phone_region,
        |phone_country_code,
        |phone_national_number,
        |phone_number_e164,
        |onboard_stage,
        |created_at,
        |updated_at
        """.stripMargin

  def insertUserDetails(userDetailsRow: UserDetailsRow): TranzactIO[Unit] =
    tzio {
      sql"""
           |INSERT INTO $frSchema.$frUserDetailsTable ($userDetailsFields)
           |VALUES ($userDetailsRow)
           |""".stripMargin.update.run
    }.unit

  def updateUserDetails(
      userID: UserID,
      onboardStageUpdate: OnboardStage,
      updatedAtUpdate: UpdatedAt,
      fullNameOptUpdate: Option[FullName] = None,
      phoneNumberOptUpdate: Option[PhoneNumber] = None,
  ): TranzactIO[UserDetailsRow] =
    tzio {
      sql"""
           |UPDATE $frSchema.$frUserDetailsTable
           |SET full_name = COALESCE($fullNameOptUpdate, full_name),
           |    phone_region = COALESCE(${phoneNumberOptUpdate.map(_.phoneRegion)}, phone_region),
           |    phone_country_code = COALESCE(${phoneNumberOptUpdate.map(_.phoneCountryCode)}, phone_country_code),
           |    phone_national_number = COALESCE(${phoneNumberOptUpdate.map(
            _.phoneNationalNumber
          )}, phone_national_number),
           |    phone_number_e164 = COALESCE(${phoneNumberOptUpdate.map(_.phoneNumberE164)}, phone_number_e164),
           |    onboard_stage = $onboardStageUpdate,
           |    updated_at = $updatedAtUpdate
           |WHERE user_id = $userID
           |RETURNING $userDetailsFields
           |""".stripMargin.query[UserDetailsRow].unique
    }

  def getUserDetails(userID: UserID): TranzactIO[Option[UserDetailsRow]] =
    tzio {
      sql"""
           |SELECT $userDetailsFields
           |FROM $frSchema.$frUserDetailsTable
           |WHERE user_id = $userID
           |""".stripMargin.query[UserDetailsRow].option
    }

  def getUserDetailsByEmail(email: Email): TranzactIO[Option[UserDetailsRow]] =
    tzio {
      sql"""
           |SELECT $userDetailsFields
           |FROM $frSchema.$frUserDetailsTable
           |WHERE email = $email
           |""".stripMargin.query[UserDetailsRow].option
    }

  def getAllUserDetailsTesting: TranzactIO[List[UserDetailsRow]] =
    tzio {
      sql"""
           |SELECT $userDetailsFields
           |FROM $frSchema.$frUserDetailsTable
           |""".stripMargin.query[UserDetailsRow].to[List]
    }
}

object UserDetailsQueries {
  val live = ZLayer.derive[UserDetailsQueries]
}
