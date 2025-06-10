package io.rikkos.gateway.repository

import io.rikkos.domain.{UpdateUserDetails, UserDetails}
import zio.*

import java.util.UUID

trait UserRepository {
  def insertUserDetails(userDetails: UserDetails): UIO[Unit]
  def updateUserDetails(updateUserDetails: UpdateUserDetails): UIO[Unit]
}

object UserRepository {

  final private class UserRepositoryMemory(userDetailsRef: Ref[Map[UUID, UserDetails]]) extends UserRepository {
    override def insertUserDetails(userDetails: UserDetails): UIO[Unit] = for {
      uuid <- ZIO.succeed(UUID.randomUUID())
      _    <- userDetailsRef.update(_.updated(uuid, userDetails))
    } yield ()

    override def updateUserDetails(updateUserDetails: UpdateUserDetails): UIO[Unit] = ???
  }

  val layer: ULayer[UserRepository] = ZLayer {
    Ref.make(Map.empty[UUID, UserDetails]).map { userDetailsRef =>
      new UserRepositoryMemory(userDetailsRef)
    }
  }
}
