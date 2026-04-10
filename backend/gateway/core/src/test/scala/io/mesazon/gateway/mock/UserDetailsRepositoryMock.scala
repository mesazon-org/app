package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.repository.domain.UserDetailsRow
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

import java.time.Instant

trait UserDetailsRepositoryMock extends ZIOTestOps, should.Matchers {
  private val insertUserDetailsCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val updateUserDetailsCounterRef: Ref[Int]     = Ref.make(0).zioValue
  private val getUserDetailsCounterRef: Ref[Int]        = Ref.make(0).zioValue
  private val getUserDetailsByEmailCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkUserDetailsRepository(
      expectedInsertUserDetailsCalls: Int = 0,
      expectedUpdateUserDetailsCalls: Int = 0,
      expectedGetUserDetailsCalls: Int = 0,
      expectedGetUserDetailsByEmailCalls: Int = 0,
  ): Assertion = {
    insertUserDetailsCounterRef.get.zioValue shouldBe expectedInsertUserDetailsCalls
    updateUserDetailsCounterRef.get.zioValue shouldBe expectedUpdateUserDetailsCalls
    getUserDetailsCounterRef.get.zioValue shouldBe expectedGetUserDetailsCalls
    getUserDetailsByEmailCounterRef.get.zioValue shouldBe expectedGetUserDetailsByEmailCalls
  }

  def userDetailsRepositoryMockLive(
      userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ): ULayer[UserDetailsRepository] = ZLayer.succeed(
    new UserDetailsRepository {

      override def insertUserDetails(email: Email, onboardStage: OnboardStage): IO[ServiceError, UserDetailsRow] =
        insertUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          maybeServiceError.fold(
            ZIO.succeed(
              UserDetailsRow(
                userID = UserID.assume("mock-user-id"),
                email = email,
                fullName = None,
                phoneNumber = None,
                onboardStage = onboardStage,
                createdAt = CreatedAt.assume(Instant.now),
                updatedAt = UpdatedAt.assume(Instant.now),
              )
            )
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)

      override def updateUserDetails(
          userID: UserID,
          onboardStageUpdate: OnboardStage,
          fullNameOptUpdate: Option[FullName],
          phoneNumberOptUpdate: Option[PhoneNumberE164],
      ): IO[ServiceError, UserDetailsRow] = updateUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
        maybeServiceError.fold(
          ZIO.succeed(
            userDetailsRows(userID)
              .copy(
                onboardStage = onboardStageUpdate,
                fullName = fullNameOptUpdate.orElse(userDetailsRows(userID).fullName),
                phoneNumber = phoneNumberOptUpdate.orElse(userDetailsRows(userID).phoneNumber),
                updatedAt = UpdatedAt.assume(Instant.now),
              )
          )
        )(ZIO.fail)
      )(ZIO.fail(_).orDie)

      override def getUserDetails(userID: UserID): IO[ServiceError, Option[UserDetailsRow]] =
        getUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          maybeServiceError.fold(
            ZIO.succeed(userDetailsRows.get(userID))
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)

      override def getUserDetailsByEmail(email: Email): IO[ServiceError, Option[UserDetailsRow]] =
        getUserDetailsByEmailCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          maybeServiceError.fold(
            ZIO.succeed(userDetailsRows.values.find(_.email == email))
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)
    }
  )
}
