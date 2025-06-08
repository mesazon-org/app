package io.rikkos.gateway.repository

import doobie.Transactor
import doobie.implicits.*
import io.rikkos.domain.UserDetails
import zio.*

trait UserRepository {
  def insertUserDetails(userDetails: UserDetails): UIO[Unit]
}

object UserRepository {

  final private case class UserRepositoryPostgreSql(xa: Transactor[Task]) extends UserRepository {

    override def insertUserDetails(userDetails: UserDetails): UIO[Unit] = ZIO.unit
  }

  def observed(repository: UserRepository): UserRepository = repository

  val live = ZLayer.fromFunction(UserRepositoryPostgreSql.apply) >>> ZLayer.fromFunction(observed)
}
