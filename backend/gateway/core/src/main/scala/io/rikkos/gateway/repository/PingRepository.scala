package io.rikkos.gateway.repository

import doobie.Transactor
import doobie.implicits.*
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.github.gaelrenoux.tranzactio.doobie.{tzio, TranzactIO}
import io.rikkos.domain.ServiceError
import zio.*

trait PingRepository {
  def ping(): IO[ServiceError.ServiceUnavailableError.DatabaseUnavailableError, Unit]
}

object PingRepository {

  private final class PingRepositoryPostgres(
      database: DatabaseOps.ServiceOps[Transactor[Task]]
  ) extends PingRepository {

    override def ping(): IO[ServiceError.ServiceUnavailableError.DatabaseUnavailableError, Unit] = database
      .transactionOrDie(
        tzio(
          sql"SELECT 1".query[Int].unique.map(_ == 1)
        )
      )
      .mapError(ServiceError.ServiceUnavailableError.DatabaseUnavailableError.apply)
      .unit
  }

  private def observed(repository: PingRepository): PingRepository = repository

  val live = ZLayer.derive[PingRepositoryPostgres] >>> ZLayer.fromFunction(observed)
}
