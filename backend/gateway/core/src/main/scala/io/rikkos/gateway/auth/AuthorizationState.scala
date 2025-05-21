package io.rikkos.gateway.auth

import cats.syntax.all.*
import io.rikkos.domain.AuthMember
import zio.*

trait AuthorizationState {
  def get(): UIO[AuthMember]
  def set(authMember: AuthMember): UIO[Unit]
}

object AuthorizationState {

  final private class AuthorizationStateImpl(authMemberFRef: FiberRef[Option[AuthMember]]) extends AuthorizationState {
    def get(): UIO[AuthMember] =
      authMemberFRef.get.someOrFailException.orDie.tapErrorCause(cause =>
        ZIO.logErrorCause("Unexpected error failed to get authorized member details", cause)
      )

    def set(authMember: AuthMember): UIO[Unit] =
      authMemberFRef.set(authMember.some)
  }

  val live: ULayer[AuthorizationState] = ZLayer.scoped(
    for {
      authedMemberDetailsFRef <- FiberRef.make(Option.empty[AuthMember])
    } yield new AuthorizationStateImpl(authedMemberDetailsFRef)
  )
}
