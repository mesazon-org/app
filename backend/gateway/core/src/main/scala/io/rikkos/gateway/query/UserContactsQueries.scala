package io.rikkos.gateway.query

import cats.syntax.all.*
import doobie.Update
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.rikkos.domain.*
import zio.*

object UserContactsQueries {

  // TODO: use named tuples when supported from chimney and doobie
  case class UpdateUserContactQuery(
      displayName: DisplayName,
      firstName: FirstName,
      phoneNumber: PhoneNumber,
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

  def insertUserContacts(userContactsTable: NonEmptyChunk[UserContactTable]): TranzactIO[Unit] = {
    val query = show"""
      | INSERT INTO
      | local_schema.users_contacts(
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

    tzio(
      Update[UserContactTable](query)
        .updateMany(userContactsTable.toList)
        .map(_ => ())
    )
  }

  def updateUserContacts(updateUserContacts: NonEmptyChunk[UpdateUserContactQuery]): TranzactIO[Unit] = {
    val query = show"""
        | UPDATE local_schema.users_contacts
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

    tzio(
      Update[UpdateUserContactQuery](query)
        .updateMany(updateUserContacts.toList)
        .map(_ => ())
    )
  }

  def getUserContacts(userID: UserID): TranzactIO[Vector[UserContactTable]] =
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
            FROM local_schema.users_contacts
            WHERE user_id = $userID""".query[UserContactTable].to[Vector]
    }
}
