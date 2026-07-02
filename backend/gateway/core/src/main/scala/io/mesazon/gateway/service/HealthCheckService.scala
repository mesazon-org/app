package io.mesazon.gateway.service

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.clients.OrganizationLogosS3Client
import io.mesazon.gateway.repository.PingRepository
import io.mesazon.gateway.{HttpErrorHandler, smithy}
import zio.*

object HealthCheckService {

  private final class HealthCheckServiceImpl(
      repository: PingRepository,
      organizationLogosS3Client: OrganizationLogosS3Client,
  ) extends smithy.HealthCheckService[ServiceTask] {

    val hardDependencies = repository.ping

    val softDependencies =
      organizationLogosS3Client.readiness.catchAllCause(cause => ZIO.logWarningCause("Soft dependency failed", cause))

    override def liveness(): IO[ServiceError, Unit] = ZIO.unit

    override def readiness(): IO[ServiceError, Unit] = hardDependencies *> softDependencies
  }

  private def observed(
      service: smithy.HealthCheckService[ServiceTask]
  ): smithy.HealthCheckService[Task] =
    new smithy.HealthCheckService[Task] {
      override def liveness(): Task[Unit] = HttpErrorHandler.errorResponseHandler(service.liveness())

      override def readiness(): Task[Unit] = HttpErrorHandler.errorResponseHandler(service.readiness())
    }

  val local = ZLayer.derive[HealthCheckServiceImpl].project[smithy.HealthCheckService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
