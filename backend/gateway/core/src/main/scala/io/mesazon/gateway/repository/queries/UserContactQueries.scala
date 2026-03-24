package io.mesazon.gateway.repository.queries

import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.{Fragment, Update}
import io.github.gaelrenoux.tranzactio.doobie.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.domain.UserContactRow
import zio.*

final class UserContactQueries(
    config: RepositoryConfig
) {

  private val frSchema           = Fragment.const(config.schema)
  private val frUserContactTable = Fragment.const(config.userContactTable)

  // TODO: use named tuples when supported from chimney and doobie
  case class UpdateUserContactQuery(
      displayName: DisplayName,
      firstName: FirstName,
      phoneNumber: PhoneNumberE164,
      lastName: Option[LastName],
      email: Option[Email],
      addressLine1: Option[AddressLine1],
      addressLine2: Option[AddressLine2],
      city: Option[City],
      postalCode: Option[PostalCode],
      company: Option[Company],
      updateAt: UpdatedAt,
      userContactID: UserContactID,
  )

  val updateUserContactsQuery =
    show"""
          | UPDATE ${config.schema}.${config.userContactTable}
          | SET
          |   display_name = ?,
          |   first_name = ?,
          |   phone_number = ?,
          |   last_name = ?,
          |   email = ?,
          |   address_line_1 = ?,
          |   address_line_2 = ?,
          |   city = ?,
          |   postal_code = ?,
          |   company = ?,
          |   updated_at = ?
          | WHERE user_contact_id = ?
          |""".stripMargin

  val insertUserContacts =
    show"""
          | INSERT INTO
          | ${config.schema}.${config.userContactTable}(
          |   user_contact_id,
          |   user_id,
          |   display_name,
          |   first_name,
          |   phone_number,
          |   last_name,
          |   email,
          |   address_line_1,
          |   address_line_2,
          |   city,
          |   postal_code,
          |   company,
          |   created_at,
          |   updated_at
          | ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          |""".stripMargin

  def insertUserContacts(userContactsRow: NonEmptyChunk[UserContactRow]): TranzactIO[Unit] =
    tzio(
      Update[UserContactRow](insertUserContacts)
        .updateMany(userContactsRow.toList)
        .map(_ => ())
    )

  def updateUserContacts(updateUserContacts: NonEmptyChunk[UpdateUserContactQuery]): TranzactIO[Unit] =
    tzio(
      Update[UpdateUserContactQuery](updateUserContactsQuery)
        .updateMany(updateUserContacts.toList)
        .map(_ => ())
    )

  def getUserContacts(userID: UserID): TranzactIO[Vector[UserContactRow]] =
    tzio {
      sql"""SELECT
              user_contact_id,
              user_id,
              display_name,
              first_name,
              phone_number,
              last_name,
              email,
              address_line_1,
              address_line_2,
              city,
              postal_code,
              company,
              created_at,
              updated_at
            FROM $frSchema.$frUserContactTable
            WHERE user_id = $userID""".query[UserContactRow].to[Vector]
    }
}

object UserContactQueries {

  val live = ZLayer.derive[UserContactQueries]
}
