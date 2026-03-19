package io.rikkos.gateway.unit.service

import io.mesazon.testkit.base.ZWordSpecBase
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.service.HealthCheckService
import io.rikkos.gateway.smithy
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
