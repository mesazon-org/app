package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.{PasswordHash, ServiceError, UserID}
import io.mesazon.gateway.repository.UserCredentialsRepository
import io.mesazon.gateway.repository.domain.UserCredentialsRow
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait UserCredentialsRepositoryMock extends ZIOTestOps with should.Matchers {
  private val insertUserCredentialsCounterRef: Ref[Int] = Ref.make(0).zioValue
  private val getUserCredentialsCounterRef: Ref[Int]    = Ref.make(0).zioValue
  private val updateUserCredentialsCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkUserCredentialsRepository(
      expectedInsertUserCredentialsCalls: Int = 0,
      expectedGetUserCredentialsCalls: Int = 0,
      expectedUpdateUserCredentialsCalls: Int = 0,
  ): Assertion = {
    insertUserCredentialsCounterRef.get.zioValue shouldBe expectedInsertUserCredentialsCalls
    getUserCredentialsCounterRef.get.zioValue shouldBe expectedGetUserCredentialsCalls
    updateUserCredentialsCounterRef.get.zioValue shouldBe expectedUpdateUserCredentialsCalls
  }

  def userCredentialsRepositoryMockLive(
      userCredentialsRows: Map[UserID, UserCredentialsRow] = Map.empty,
      serviceErrorOpt: Option[ServiceError] = None,
  ): ULayer[UserCredentialsRepository] = ZLayer.succeed(
    new UserCredentialsRepository {

      override def insertUserCredentials(userID: UserID, passwordHash: PasswordHash): IO[ServiceError, Unit] =
        insertUserCredentialsCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.unit
          )(ZIO.fail)

      override def getUserCredentials(userID: UserID): IO[ServiceError, Option[UserCredentialsRow]] =
        getUserCredentialsCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.succeed(userCredentialsRows.get(userID))
          )(ZIO.fail)

      override def updateUserCredentials(userID: UserID, passwordHashUpdate: PasswordHash): IO[ServiceError, Unit] =
        updateUserCredentialsCounterRef.incrementAndGet *>
          serviceErrorOpt.fold(
            ZIO.unit
          )(ZIO.fail)
    }
  )
}
