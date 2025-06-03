package io.rikkos.gateway.auth

import cats.syntax.all.*
import io.rikkos.domain.AuthedUser
import zio.*

trait AuthorizationState {
  def get(): UIO[AuthedUser]
  def set(authMember: AuthedUser): UIO[Unit]
}

object AuthorizationState {

  final private class AuthorizationStateImpl(authMemberFRef: FiberRef[Option[AuthedUser]]) extends AuthorizationState {
    def get(): UIO[AuthedUser] =
      authMemberFRef.get.someOrFailException.orDie.tapErrorCause(cause =>
        ZIO.logErrorCause("Unexpected error failed to get authorized member details", cause)
      )

    def set(authMember: AuthedUser): UIO[Unit] =
      authMemberFRef.set(authMember.some)
  }

  val live: ULayer[AuthorizationState] = ZLayer.scoped(
    for {
      authedMemberDetailsFRef <- FiberRef.make(Option.empty[AuthedUser])
    } yield new AuthorizationStateImpl(authedMemberDetailsFRef)
  )
}
