package io.mesazon.gateway.repository.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.postgres.implicits.*
import org.typelevel.doobie.util.fragments.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.*
import zio.*

final class UserDetailsQueries(config: RepositoryConfig) {

  private val frSchema           = Fragment.const(config.schema)
  private val frUserDetailsTable = Fragment.const(config.userDetailsTable)
  private val frTable            = frSchema ++ fr0"." ++ frUserDetailsTable

  val frUserDetailsFields =
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
      val q =
        fr"INSERT INTO" ++ frTable ++
          fr"(" ++ frUserDetailsFields ++ fr")" ++
          fr"VALUES (" ++ fr"$userDetailsRow" ++ fr")"

      q.update.run.void
    }

  def updateUserDetails(
      userID: UserID,
      onboardStageUpdate: OnboardStage,
      updatedAtUpdate: UpdatedAt,
      fullNameOptUpdate: Option[FullName] = None,
      phoneNumberOptUpdate: Option[PhoneNumber] = None,
  ): TranzactIO[UserDetailsRow] = {
    val updates = NonEmptyList.of(
      fr"onboard_stage = $onboardStageUpdate",
      fr"updated_at = $updatedAtUpdate",
    ) ++ List(
      fullNameOptUpdate.map(v => fr"full_name = $v"),
      phoneNumberOptUpdate.map(v => fr"phone_region = ${v.phoneRegion}"),
      phoneNumberOptUpdate.map(v => fr"phone_country_code = ${v.phoneCountryCode}"),
      phoneNumberOptUpdate.map(v => fr"phone_national_number = ${v.phoneNationalNumber}"),
      phoneNumberOptUpdate.map(v => fr"phone_number_e164 = ${v.phoneNumberE164}"),
    ).flatten

    tzio {
      val q =
        fr"UPDATE" ++ frTable ++
          set(updates) ++
          whereAnd(fr"user_id = $userID") ++
          fr"RETURNING" ++ frUserDetailsFields

      q.query[UserDetailsRow].unique
    }
  }

  def getUserDetails(userID: UserID): TranzactIO[Option[UserDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserDetailsFields ++
          fr"FROM" ++ frTable ++
          whereAnd(fr"user_id = $userID")

      q.query[UserDetailsRow].option
    }

  def getUserDetailsByEmail(email: Email): TranzactIO[Option[UserDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserDetailsFields ++
          fr"FROM" ++ frTable ++
          whereAnd(fr"email = $email")

      q.query[UserDetailsRow].option
    }

  // Testing
  def getAllUserDetailsTesting: TranzactIO[List[UserDetailsRow]] =
    tzio {
      val q =
        fr"SELECT" ++ frUserDetailsFields ++
          fr"FROM" ++ frTable

      q.query[UserDetailsRow].to[List]
    }
}

object UserDetailsQueries {
  val live = ZLayer.derive[UserDetailsQueries]
}
