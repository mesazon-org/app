package io.rikkos.gateway.mock

import io.rikkos.clock.TimeProvider
import io.rikkos.domain.ServiceError.ConflictError
import io.rikkos.domain.{AuthedUser, Email, OnboardUserDetails, UpdateUserDetails, UserID}
import io.rikkos.gateway.auth.{AuthorizationService, AuthorizationState}
import io.rikkos.gateway.repository.UserRepository
import org.http4s.Request
import zio.*

import java.time.Clock

def userRepositoryMockLive(
    userDetailsRef: Ref[Set[OnboardUserDetails]],
    maybeError: Option[Throwable] = None,
): ULayer[UserRepository] =
  ZLayer.succeed(
    new UserRepository {

      override def insertUserDetails(
          userID: UserID,
          email: Email,
          userDetails: OnboardUserDetails,
      ): IO[ConflictError.UserAlreadyExists, Unit] =
        maybeError.fold(userDetailsRef.set(Set(userDetails)))(ZIO.fail(_).orDie)

      override def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit] =
        ZIO.unit
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

def timeProviderMockLive(clock: Clock): ULayer[TimeProvider] =
  ZLayer.succeed(clock) >>> TimeProvider.live
