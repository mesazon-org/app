package io.rikkos.gateway.query

import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.rikkos.domain.*

object UserDetailsQueries {

  def insertUserDetailsQuery(userDetailsTable: UserDetailsTable): TranzactIO[Unit] =
    tzio {
      sql"""INSERT INTO local_schema.user_details (
          user_id,
          email,
          first_name,
          last_name,
          country_code,
          phone_number,
          address_line_1,
          address_line_2,
          city,
          postal_code,
          company,
          created_at,
          updated_at
        ) VALUES (
          ${userDetailsTable.userID},
          ${userDetailsTable.email},
          ${userDetailsTable.firstName},
          ${userDetailsTable.lastName},
          ${userDetailsTable.countryCode},
          ${userDetailsTable.phoneNumber},
          ${userDetailsTable.addressLine1},
          ${userDetailsTable.addressLine2},
          ${userDetailsTable.city},
          ${userDetailsTable.postalCode},
          ${userDetailsTable.company},
          ${userDetailsTable.createdAt},
          ${userDetailsTable.updatedAt}
        )""".update.run.map(_ => ())
    }

  def getUserDetailsQuery(userID: UserID): TranzactIO[Option[UserDetailsTable]] =
    tzio {
      sql"""SELECT
              user_id,
              email,
              first_name,
              last_name,
              country_code,
              phone_number,
              address_line_1,
              address_line_2,
              city,
              postal_code,
              company,
              created_at,
              updated_at
              FROM
              local_schema.user_details
              WHERE user_id = $userID""".query[UserDetailsTable].option
    }
}
