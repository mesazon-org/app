package io.mesazon.gateway.service

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.repository.PingRepository
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object HealthCheckService {

  private final class HealthCheckServiceImpl(
      repository: PingRepository
  ) extends smithy.HealthCheckService[ServiceTask] {
    override def liveness(): IO[ServiceError, Unit] = ZIO.unit

    override def readiness(): IO[ServiceError, Unit] =
      repository.ping()
  }

  private def observed(
      service: smithy.HealthCheckService[ServiceTask]
  ): smithy.HealthCheckService[Task] =
    new smithy.HealthCheckService[Task] {
      override def liveness(): Task[Unit] = HttpErrorHandler.errorResponseHandler(service.liveness())

      override def readiness(): Task[Unit] = HttpErrorHandler.errorResponseHandler(service.readiness())
    }

  val live = ZLayer.derive[HealthCheckServiceImpl] >>> ZLayer.fromFunction(observed)
}
