package io.rikkos.gateway.service

import io.rikkos.domain.ServiceError
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object HealthCheckService {

  private final class HealthCheckServiceImpl() extends smithy.HealthCheckService[[A] =>> IO[ServiceError, A]] {
    override def liveness(): IO[ServiceError, Unit] = ZIO.unit

    override def readiness(): IO[ServiceError, Unit] = ZIO.unit
  }

  private def observed(
      service: smithy.HealthCheckService[[A] =>> IO[ServiceError, A]]
  ): smithy.HealthCheckService[Task] =
    new smithy.HealthCheckService[Task] {
      override def liveness(): Task[Unit] = HttpErrorHandler.errorResponseHandler(service.liveness())

      override def readiness(): Task[Unit] = HttpErrorHandler.errorResponseHandler(service.readiness())
    }

  val live = ZLayer.derive[HealthCheckServiceImpl] >>> ZLayer.fromFunction(observed)
}
