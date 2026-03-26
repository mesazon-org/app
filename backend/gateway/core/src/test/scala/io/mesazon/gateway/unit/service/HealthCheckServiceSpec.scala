package io.mesazon.gateway.unit.service

import io.mesazon.gateway.mock.*
import io.mesazon.gateway.service.HealthCheckService
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class HealthCheckServiceSpec extends ZWordSpecBase {

  val healthCheckServiceEnv = ZIO.service[smithy.HealthCheckService[Task]]

  "HealthCheckService" when {
    "liveness" should {
      "return a successful response" in {
        val healthCheckService = healthCheckServiceEnv.provide(HealthCheckService.live, pingRepositoryMockLive())

        healthCheckService.flatMap(_.liveness()).zioEither.isRight shouldBe true
      }
    }

    "readiness" should {
      "return a successful response" in {
        val healthCheckService = healthCheckServiceEnv.provide(HealthCheckService.live, pingRepositoryMockLive())

        healthCheckService.flatMap(_.readiness()).zioEither.isRight shouldBe true
      }
    }
  }
}
