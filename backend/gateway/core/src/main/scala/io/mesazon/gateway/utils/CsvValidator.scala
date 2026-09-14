package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import zio.*

trait CsvValidator {
  def validate(csvByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, Unit]
}

object CsvValidator {

  private final class CsvValidatorImpl extends CsvValidator {
    override def validate(csvByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, Unit] =
      ZIO.die(new NotImplementedError("CsvValidator.validate is not implemented yet"))
  }

  val live = ZLayer.derive[CsvValidatorImpl].project[CsvValidator](identity)
}
