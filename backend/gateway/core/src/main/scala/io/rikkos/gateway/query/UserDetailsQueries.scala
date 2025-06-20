package io.rikkos.gateway.query

import doobie.implicits.*
import doobie.postgres.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.*
import io.rikkos.domain.*

object UserDetailsQueries {

  def insertUserDetailsQuery(
      userID: UserID,
      email: Email,
      firstName: FirstName,
      lastName: LastName,
      countryCode: CountryCode,
      phoneNumber: PhoneNumber,
      addressLine1: AddressLine1,
      addressLine2: Option[AddressLine2],
      city: City,
      postalCode: PostalCode,
      company: Company,
      createdAt: CreatedAt,
      updatedAt: UpdatedAt,
  ): TranzactIO[Unit] =
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
          $userID,
          $email,
          $firstName,
          $lastName,
          $countryCode,
          $phoneNumber,
          $addressLine1,
          $addressLine2,
          $city,
          $postalCode,
          $company,
          $createdAt,
          $updatedAt
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
              company
              created_at,
              updated_at
              FROM
              local_schema.user_details
              WHERE user_id = $userID""".query[UserDetailsTable].option
    }
}
