package io.mesazon.gateway.mock

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.repository.PingRepository
import io.mesazon.testkit.base.ZIOTestOps
import org.scalatest.Assertion
import org.scalatest.matchers.should
import zio.*

trait PingRepositoryMock extends ZIOTestOps, should.Matchers {
  private val pingCounterRef: Ref[Int] = Ref.make(0).zioValue

  def checkPingRepository(expectedPingCalls: Int = 0): Assertion =
    pingCounterRef.get.zioValue shouldBe expectedPingCalls

  def pingRepositoryLive(
      serviceErrorOpt: Option[ServiceError.ServiceUnavailableError.DatabaseUnavailableError] = None
  ): ULayer[PingRepository] =
    ZLayer.succeed(
      new PingRepository {

        override def ping(): IO[ServiceError.ServiceUnavailableError.DatabaseUnavailableError, Unit] =
          pingCounterRef.incrementAndGet *>
            serviceErrorOpt.fold(
              ZIO.unit
            )(ZIO.fail)
      }
    )

}
