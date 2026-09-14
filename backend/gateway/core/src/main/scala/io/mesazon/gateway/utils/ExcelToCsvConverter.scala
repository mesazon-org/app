package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import zio.*

trait ExcelToCsvConverter {
  def convert(excelByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, FileByteStreamScanned]
}

object ExcelToCsvConverter {

  private final class ExcelToCsvConverterImpl extends ExcelToCsvConverter {
    override def convert(
        excelByteStreamScanned: FileByteStreamScanned
    ): ZIO[Scope, ServiceError, FileByteStreamScanned] =
      ZIO.die(new NotImplementedError("ExcelToCsvConverter.convert is not implemented yet"))
  }

  val live = ZLayer.derive[ExcelToCsvConverterImpl].project[ExcelToCsvConverter](identity)
}
