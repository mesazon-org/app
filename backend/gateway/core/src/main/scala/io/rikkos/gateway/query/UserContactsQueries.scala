package io.rikkos.gateway.query

import cats.syntax.all.*
import doobie.Update
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.rikkos.domain.*

object UserContactsQueries {

  def upsertUserContacts(
      userContactTable: Vector[UserContactTable]
  ): TranzactIO[Unit] = {
    val query =
      show"""
        | INSERT INTO local_schema.users_contacts(
        |   user_contact_id,
        |   user_id,
        |   contact_id,
        |   display_name,
        |   first_name,
        |   phone_number,
        |   last_name,
        |   company,
        |   email,
        |   address_line_1,
        |   address_line_2,
        |   city,
        |   postal_code,
        |   created_at,
        |   updated_at
        | ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        | ON CONFLICT (user_contact_id) DO UPDATE SET
        |   display_name = EXCLUDED.display_name,
        |   first_name = EXCLUDED.first_name,
        |   phone_number = EXCLUDED.phone_number,
        |   last_name = EXCLUDED.last_name,
        |   company = EXCLUDED.company,
        |   email = EXCLUDED.email,
        |   address_line_1 = EXCLUDED.address_line_1,
        |   address_line_2 = EXCLUDED.address_line_2,
        |   city = EXCLUDED.city,
        |   postal_code = EXCLUDED.postal_code,
        |   updated_at = EXCLUDED.updated_at
        |""".stripMargin

    tzio(
      Update[UserContactTable](query)
        .updateMany(userContactTable)
        .map(_ => ())
    )
  }
//    tzio {
//      sql"""INSERT INTO local_schema.users_contacts (
//              user_contact_id,
//              user_id,
//              display_name,
//              first_name,
//              phone_number,
//              last_name,
//              company,
//              email,
//              address_line_1,
//              address_line_2,
//              city,
//              postal_code,
//              created_at,
//              updated_at
//            ) SELECT *
//            ON CONFLICT (user_contact_id) DO UPDATE SET
//                display_name = EXCLUDED.display_name,
//                first_name = EXCLUDED.first_name,
//                phone_number = EXCLUDED.phone_number,
//                last_name = EXCLUDED.last_name,
//                company = EXCLUDED.company,
//                email = EXCLUDED.email,
//                address_line_1 = EXCLUDED.address_line_1,
//                address_line_2 = EXCLUDED.address_line_2,
//                city = EXCLUDED.city,
//                postal_code = EXCLUDED.postal_code,
//                updated_at = EXCLUDED.updated_at
//            """.update.run.map(_ => ())
//    }

  def getUserContacts(userID: UserID): TranzactIO[Vector[UserContactTable]] =
    tzio {
      sql"""SELECT
              user_contact_id,
              user_id,
              contact_id,
              display_name,
              first_name,
              phone_number,
              last_name,
              company,
              email,
              address_line_1,
              address_line_2,
              city,
              postal_code,
              created_at,
              updated_at
            FROM local_schema.users_contacts
            WHERE user_id = $userID""".query[UserContactTable].to[Vector]
    }
}
