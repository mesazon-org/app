package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.*
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
      hashPasswordOutput: Option[PasswordHash] = None,
      verifyPasswordOutput: Boolean = true,
      serviceErrorOpt: Option[ServiceError] = None,
  ): ULayer[PasswordService] =
    ZLayer.succeed(
      new PasswordService {
        override def hashPassword(password: Password): IO[ServiceError, PasswordHash] =
          hashPasswordCounterRef.incrementAndGet *> serviceErrorOpt.fold(
            ZIO.succeed(hashPasswordOutput.get)
          )(ZIO.fail)

        override def verifyPassword(password: Password, passwordHash: PasswordHash): IO[ServiceError, Boolean] =
          verifyPasswordCounterRef.incrementAndGet *> serviceErrorOpt.fold(
            ZIO.succeed(verifyPasswordOutput)
          )(ZIO.fail)
      }
    )
}
