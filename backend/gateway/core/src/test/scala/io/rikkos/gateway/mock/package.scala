package io.rikkos.gateway.mock

import io.rikkos.domain.{AuthedUser, UserDetails}
import io.rikkos.gateway.auth.{AuthorizationService, AuthorizationState}
import io.rikkos.gateway.repository.UserRepository
import org.http4s.Request
import zio.*

def userRepositoryMockLive(
    userDetailsRef: Ref[Set[UserDetails]],
    maybeError: Option[Throwable] = None,
): ULayer[UserRepository] =
  ZLayer.succeed(
    new UserRepository {
      override def insertUserDetails(userDetails: UserDetails): UIO[Unit] =
        maybeError.fold(userDetailsRef.set(Set(userDetails)))(ZIO.fail(_).orDie)
    }
  )

def authorizationStateMockLive(authedUser: AuthedUser): ULayer[AuthorizationState] =
  ZLayer.succeed(
    new AuthorizationState {
      override def get(): UIO[AuthedUser]                 = ZIO.succeed(authedUser)
      override def set(authedUser: AuthedUser): UIO[Unit] = ZIO.unit
    }
  )

def authorizationServiceMockLive(maybeError: Option[Throwable] = None): ULayer[AuthorizationService[Throwable]] =
  ZLayer.succeed(
    new AuthorizationService[Throwable] {
      override def auth(request: Request[Task]): Task[Unit] =
        maybeError.fold(ZIO.unit)(ZIO.fail(_))
    }
  )
