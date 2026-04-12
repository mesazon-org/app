package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.PasswordService
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait PasswordServiceMock extends ZIOTestOps, should.Matchers {
  private val hashPasswordCounterRef: Ref[Int]   = Ref.make(0).zioValue
  private val verifyPasswordCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkPasswordService(
      expectedHashPasswordCalls: Int = 0,
      expectedVerifyPasswordCalls: Int = 0,
  ): Assertion = {
    hashPasswordCounterRef.get.zioValue shouldBe expectedHashPasswordCalls
    verifyPasswordCounterRef.get.zioValue shouldBe expectedVerifyPasswordCalls
  }

  def passwordServiceMockLive(
      maybeServiceError: Option[ServiceError] = None
  ): ULayer[PasswordService] =
    ZLayer.succeed(
      new PasswordService {
        override def hashPassword(password: Password): IO[ServiceError, PasswordHash] =
          hashPasswordCounterRef.incrementAndGet *> maybeServiceError.fold(
            ZIO.succeed(PasswordHash.assume(s"hashed-${password.value}"))
          )(ZIO.fail)

        override def verifyPassword(password: Password, passwordHash: PasswordHash): IO[ServiceError, Boolean] =
          verifyPasswordCounterRef.incrementAndGet *> maybeServiceError.fold(
            ZIO.succeed(passwordHash.value == password.value)
          )(ZIO.fail)
      }
    )
}
