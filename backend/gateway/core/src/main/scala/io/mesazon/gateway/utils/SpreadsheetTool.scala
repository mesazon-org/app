package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.{ServiceError, SupportedMediaType}
import zio.*

trait SpreadsheetTool {
  def convertValidateCsv(
      fileByteStreamScanned: FileByteStreamScanned,
      supportedMediaType: SupportedMediaType,
  ): ZIO[Scope, ServiceError, ValidatedCsvByteStream]
}

object SpreadsheetTool {

  private final class SpreadsheetToolImpl extends SpreadsheetTool {
    override def convertValidateCsv(
        fileByteStreamScanned: FileByteStreamScanned,
        supportedMediaType: SupportedMediaType,
    ): ZIO[Scope, ServiceError, ValidatedCsvByteStream] =
      ZIO.die(new NotImplementedError("SpreadsheetTool.convertValidateCsv is not implemented yet"))
  }

  val live = ZLayer.derive[SpreadsheetToolImpl].project[SpreadsheetTool](identity)
}
