package io.rikkos.gateway.auth

import cats.syntax.all.*
import io.rikkos.domain.gateway.AuthedUser
import zio.*

trait AuthorizationState {
  def get(): UIO[AuthedUser]
  def set(authedUser: AuthedUser): UIO[Unit]
}

object AuthorizationState {

  private final class AuthorizationStateImpl(authedUserFRef: FiberRef[Option[AuthedUser]]) extends AuthorizationState {
    def get(): UIO[AuthedUser] =
      authedUserFRef.get.someOrFailException.orDie.tapErrorCause(cause =>
        ZIO.logErrorCause("Unexpected error failed to get authorized user details", cause)
      )

    def set(authedUser: AuthedUser): UIO[Unit] =
      authedUserFRef.set(authedUser.some)
  }

  private def observed(state: AuthorizationState): AuthorizationState = state

  val live = ZLayer.derive[AuthorizationStateImpl] >>> ZLayer.fromFunction(observed)
}
