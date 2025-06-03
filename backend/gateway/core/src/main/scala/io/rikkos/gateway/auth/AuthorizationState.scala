package io.rikkos.gateway.auth

import cats.syntax.all.*
import io.rikkos.domain.AuthedUser
import zio.*

trait AuthorizationState {
  def get(): UIO[AuthedUser]
  def set(authedUser: AuthedUser): UIO[Unit]
}

object AuthorizationState {

  final private class AuthorizationStateImpl(authedUserFRef: FiberRef[Option[AuthedUser]]) extends AuthorizationState {
    def get(): UIO[AuthedUser] =
      authedUserFRef.get.someOrFailException.orDie.tapErrorCause(cause =>
        ZIO.logErrorCause("Unexpected error failed to get authorized user details", cause)
      )

    def set(authedUser: AuthedUser): UIO[Unit] =
      authedUserFRef.set(authedUser.some)
  }

  val live: ULayer[AuthorizationState] = ZLayer.scoped(
    for {
      authedUserDetailsFRef <- FiberRef.make(Option.empty[AuthedUser])
    } yield new AuthorizationStateImpl(authedUserDetailsFRef)
  )
}
