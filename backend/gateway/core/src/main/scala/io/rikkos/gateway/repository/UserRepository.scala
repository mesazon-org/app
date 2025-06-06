package io.rikkos.gateway.repository

import io.rikkos.domain.{EditUserDetails, UserDetails}
import zio.*

import java.util.UUID

trait UserRepository {
  def insertUserDetails(userDetails: UserDetails): UIO[Unit]
  def editUserDetails(editUserDetails: EditUserDetails): UIO[Unit]
}

object UserRepository {

  final private class UserRepositoryMemory(userDetailsRef: Ref[Map[UUID, UserDetails]]) extends UserRepository {
    override def insertUserDetails(userDetails: UserDetails): UIO[Unit] = for {
      uuid <- ZIO.succeed(UUID.randomUUID())
      _    <- userDetailsRef.update(_.updated(uuid, userDetails))
    } yield ()

    override def editUserDetails(editUserDetails: EditUserDetails): UIO[Unit] = ???
  }

  val layer: ULayer[UserRepository] = ZLayer {
    Ref.make(Map.empty[UUID, UserDetails]).map { userDetailsRef =>
      new UserRepositoryMemory(userDetailsRef)
    }
  }
}
