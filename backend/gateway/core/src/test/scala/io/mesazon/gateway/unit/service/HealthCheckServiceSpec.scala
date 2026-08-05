package io.mesazon.gateway.unit.service

import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.PingRepository
import io.mesazon.gateway.service.{HealthCheckService, ServiceTask}
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class HealthCheckServiceSpec extends ZWordSpecBase {

  val healthCheckServiceEnv = ZIO.service[smithy.HealthCheckService[ServiceTask]]

  "HealthCheckService" when {
    "liveness" should {
      "return a successful response" in new TestContext {
        val healthCheckService = buildHealthCheckService

        healthCheckService.liveness().zioEither.isRight shouldBe true
      }
    }

    "readiness" should {
      "return a successful response" in new TestContext {

        (() => pingRepositoryMock.ping)
          .expects()
          .returningZIOUnit

        (() => s3ClientOrganizationMediaMock.readiness)
          .expects()
          .returningZIOUnit

        val healthCheckService = buildHealthCheckService

        healthCheckService.readiness().zioEither.isRight shouldBe true
      }
    }
  }

  trait TestContext {

    val pingRepositoryMock            = mock[PingRepository]
    val s3ClientOrganizationMediaMock = mock[S3ClientOrganizationMedia]

    def buildHealthCheckService: smithy.HealthCheckService[ServiceTask] =
      ZIO
        .service[smithy.HealthCheckService[ServiceTask]]
        .provide(
          HealthCheckService.local,
          ZLayer.succeed(pingRepositoryMock),
          ZLayer.succeed(s3ClientOrganizationMediaMock),
        )
        .zioValue
  }
}
