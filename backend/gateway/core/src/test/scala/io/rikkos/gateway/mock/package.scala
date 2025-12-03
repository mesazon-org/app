package io.rikkos.gateway.mock

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.rikkos.clock.TimeProvider
import io.rikkos.domain.*
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import io.rikkos.gateway.auth.*
import io.rikkos.gateway.repository.UserContactsRepository
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.validation.*
import io.rikkos.gateway.validation.PhoneNumberValidator.PhoneNumberParams
import io.rikkos.generator.IDGenerator
import org.http4s.Request
import zio.*

import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

def userRepositoryMockLive(
    insertUserDetailsCounterRef: Ref[Int],
    updateUserDetailsCounterRef: Ref[Int],
    maybeError: Option[Throwable] = None,
): ULayer[UserRepository] =
  ZLayer.succeed(
    new UserRepository {

      override def insertUserDetails(
          userID: UserID,
          email: Email,
          userDetails: OnboardUserDetails,
      ): IO[ServiceError.ConflictError.UserAlreadyExists, Unit] =
        maybeError.fold(insertUserDetailsCounterRef.incrementAndGet.unit)(ZIO.fail(_).orDie)

      override def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit] =
        maybeError.fold(updateUserDetailsCounterRef.incrementAndGet.unit)(ZIO.fail(_).orDie)
    }
  )

def userContactsRepositoryMockLive(
    upsertUserContactsCounterRef: Ref[Int],
    maybeError: Option[Throwable] = None,
): ULayer[UserContactsRepository] = ZLayer.succeed(
  new UserContactsRepository {
    override def upsertUserContacts(userID: UserID, upsertUserContacts: NonEmptyChunk[UpsertUserContact]): UIO[Unit] =
      maybeError.fold(upsertUserContactsCounterRef.incrementAndGet.unit)(ZIO.fail(_).orDie)
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

def idGeneratorMockLive: ULayer[IDGenerator] =
  ZLayer.succeed {
    val atomicInt = new AtomicInteger(0)

    new IDGenerator {
      override def generate: UIO[String] = ZIO.succeed(atomicInt.incrementAndGet().toString)
    }
  }

def timeProviderMockLive(clock: Clock): ULayer[TimeProvider] =
  ZLayer.succeed(clock) >>> TimeProvider.live
