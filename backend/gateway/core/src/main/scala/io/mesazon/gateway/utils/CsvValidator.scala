package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import org.apache.commons.csv.{CSVFormat, CSVParser}
import zio.*

import java.io.StringReader
import java.nio.charset.StandardCharsets

trait CsvValidator {
  def validate(csvByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, Unit]
}

object CsvValidator {

  private final class CsvValidatorImpl extends CsvValidator {
    override def validate(csvByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, Unit] =
      for {
        csvBytes <- csvByteStreamScanned.value.runCollect
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to read CSV file", Some(e)))
        csvText = new String(csvBytes.toArray, StandardCharsets.UTF_8)
        _ <- ZIO.acquireReleaseWith(
          ZIO
            .attemptBlocking(CSVParser.parse(new StringReader(csvText), CSVFormat.DEFAULT))
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(e)))
        )(csvParser => ZIO.attemptBlocking(csvParser.close()).ignoreLogged) { csvParser =>
          ZIO
            .attemptBlocking(csvParser.getRecords())
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(e)))
        }
      } yield ()
  }

  val live = ZLayer.derive[CsvValidatorImpl].project[CsvValidator](identity)
}
