package io.rikkos.gateway.repository

import doobie.*
import doobie.implicits.*
import io.rikkos.domain.UserDetails
import zio.*
import zio.interop.catz.*

trait UserRepository {
  def insertUserDetails(userDetails: UserDetails): UIO[Unit]
}

object UserRepository {

  final private case class UserRepositoryPostgreSql(xa: Transactor[Task]) extends UserRepository {
    val insertInto       = Fragment.const("INSERT INTO")
    val userDetailsTable = Fragment.const("user_details")
    val userDetailsCols = Fragment.const(
      "(user_id, email, first_name, last_name, country_code, phone_number, address_line_1, address_line_2, city, postal_code, company, created_at, updated_at)"
    )

    def insertUserDetailsStatement(userDetails: UserDetails) =
      (insertInto ++ userDetailsTable++ userDetailsCols ++
        sql"""
           VALUES (${userDetails.userID}, ${userDetails.email}, ${userDetails.firstName}, ${userDetails.lastName},
                   ${userDetails.countryCode}, ${userDetails.phoneNumber}, ${userDetails.addressLine1},
                   ${userDetails.addressLine2}, ${userDetails.city}, ${userDetails.postalCode},
                   ${userDetails.company}, NOW(), NOW())
           """).updateWithLabel("insertUserDetails")

    override def insertUserDetails(userDetails: UserDetails): UIO[Unit] =
      insertUserDetailsStatement(userDetails).run
        .transact(xa)
        .unit
        .orDie
  }

  def observed(repository: UserRepository): UserRepository = repository

  val live = ZLayer.fromFunction(UserRepositoryPostgreSql.apply) >>> ZLayer.fromFunction(observed)
}
