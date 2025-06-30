package io.rikkos.gateway.mock

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.domain.{AuthedUser, PhoneNumber, UserDetails}
import io.rikkos.gateway.auth.{AuthorizationService, AuthorizationState}
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.validation.ServiceValidator.{DomainValidator, PhoneNumberParams}
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
        maybeError.fold(ZIO.unit)(ZIO.fail(_).orDie)
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

def phoneNumberValidatorMockLive(): ULayer[DomainValidator[PhoneNumberParams, PhoneNumber]] =
  ZLayer.succeed(
    new DomainValidator[PhoneNumberParams, PhoneNumber] {
      override def validate(rawData: PhoneNumberParams): UIO[ValidatedNec[InvalidFieldError, PhoneNumber]] =
        ZIO.succeed(PhoneNumber.assume(rawData.phoneNationalNumber).validNec)
    }
  )

def timeProviderMockLive(clock: Clock): ULayer[TimeProvider] =
  ZLayer.succeed(clock) >>> TimeProvider.live
