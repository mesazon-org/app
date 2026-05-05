package io.mesazon.gateway.state

import cats.syntax.all.*
import io.mesazon.domain.gateway.AuthedUser
import zio.*

trait AuthState {
  def get(): UIO[AuthedUser]
  def set(authedUser: AuthedUser): UIO[Unit]
}

object AuthState {

  private final class AuthStateImpl(authedUserFRef: FiberRef[Option[AuthedUser]]) extends AuthState {
    def get(): UIO[AuthedUser] =
      authedUserFRef.get.someOrFailException.orDie.tapErrorCause(cause =>
        ZIO.logErrorCause("Unexpected error failed to get authorized user details", cause)
      )

    def set(authedUser: AuthedUser): UIO[Unit] =
      authedUserFRef.set(authedUser.some)
  }

  private def observed(state: AuthState): AuthState = state

  val live = ZLayer.derive[AuthStateImpl] >>> ZLayer.fromFunction(observed)
}
