package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.repository.domain.UserDetailsRow
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

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
      insertUserDetailsOutput: Option[UserDetailsRow] = None,
      updateUserDetailsOutput: Option[UserDetailsRow] = None,
      getUserDetailsOutput: Option[UserDetailsRow] = None,
      getUserDetailsByEmailOutput: Option[UserDetailsRow] = None,
      serviceErrorOpt: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ): ULayer[UserDetailsRepository] = ZLayer.succeed(
    new UserDetailsRepository {

      override def insertUserDetails(email: Email, onboardStage: OnboardStage): IO[ServiceError, UserDetailsRow] =
        insertUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          serviceErrorOpt.fold(
            ZIO.succeed(insertUserDetailsOutput.get)
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)

      override def updateUserDetails(
          userID: UserID,
          onboardStageUpdate: OnboardStage,
          fullNameOptUpdate: Option[FullName],
          phoneNumberOptUpdate: Option[PhoneNumber],
      ): IO[ServiceError, UserDetailsRow] = updateUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
        serviceErrorOpt.fold(
          ZIO.succeed(updateUserDetailsOutput.get)
        )(ZIO.fail)
      )(ZIO.fail(_).orDie)

      override def getUserDetails(userID: UserID): IO[ServiceError, Option[UserDetailsRow]] =
        getUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          serviceErrorOpt.fold(
            ZIO.succeed(getUserDetailsOutput)
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)

      override def getUserDetailsByEmail(email: Email): IO[ServiceError, Option[UserDetailsRow]] =
        getUserDetailsByEmailCounterRef.incrementAndGet *> maybeUnexpectedError.fold(
          serviceErrorOpt.fold(
            ZIO.succeed(getUserDetailsByEmailOutput)
          )(ZIO.fail)
        )(ZIO.fail(_).orDie)
    }
  )
}
