package io.rikkos.gateway.repository.queries

import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.rikkos.domain.gateway.*
import io.rikkos.gateway.repository.domain.UserDetailsRow

object UserDetailsQueries {

  case class UpdateUserDetailsQuery(
      userID: UserID,
      firstName: Option[FirstName],
      lastName: Option[LastName],
      phoneNumber: Option[PhoneNumber],
      addressLine1: Option[AddressLine1],
      addressLine2: Option[AddressLine2],
      city: Option[City],
      postalCode: Option[PostalCode],
      company: Option[Company],
      updatedAt: UpdatedAt,
  )

  def insertUserDetailsQuery(userDetailsTable: UserDetailsRow): TranzactIO[Unit] =
    tzio {
      sql"""INSERT INTO local_schema.users_details (
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
          ${userDetailsTable.userID},
          ${userDetailsTable.email},
          ${userDetailsTable.firstName},
          ${userDetailsTable.lastName},
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
              local_schema.users_details
              WHERE user_id = $userID""".query[UserDetailsRow].option
    }

  def updateUserDetailsQuery(query: UpdateUserDetailsQuery): TranzactIO[Unit] =
    tzio {
      sql"""
      UPDATE local_schema.users_details
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
