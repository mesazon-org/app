package io.mesazon.gateway.unit.service

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.mock.PingRepositoryMock
import io.mesazon.gateway.service.{HealthCheckService, ServiceTask}
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class HealthCheckServiceSpec extends ZWordSpecBase {

  val healthCheckServiceEnv = ZIO.service[smithy.HealthCheckService[ServiceTask]]

  "HealthCheckService" when {
    "liveness" should {
      "return a successful response" in new TestContext {
        val healthCheckService = buildHealthCheckService()

        healthCheckService.liveness().zioEither.isRight shouldBe true
      }
    }

    "readiness" should {
      "return a successful response" in new TestContext {
        val healthCheckService = buildHealthCheckService()

        healthCheckService.readiness().zioEither.isRight shouldBe true
      }
    }
  }

  trait TestContext extends PingRepositoryMock {

    def buildHealthCheckService(
        pingRepositoryServiceErrorOpt: Option[ServiceError.ServiceUnavailableError.DatabaseUnavailableError] = None
    ): smithy.HealthCheckService[ServiceTask] =
      ZIO
        .service[smithy.HealthCheckService[ServiceTask]]
        .provide(
          HealthCheckService.local,
          pingRepositoryLive(
            serviceErrorOpt = pingRepositoryServiceErrorOpt
          ),
        )
        .zioValue
  }
}
