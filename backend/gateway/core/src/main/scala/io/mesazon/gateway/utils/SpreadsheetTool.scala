package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import org.apache.commons.csv.*
import org.apache.poi.ss.usermodel.*
import zio.*
import zio.stream.*

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.*
import scala.jdk.CollectionConverters.*

trait SpreadsheetTool {
  def convertExcelToCsv(
      excelFileScannedPath: FileScannedPath
  ): ZStream[Scope, ServiceError, CSVRecord]

  def convertToCsv(
      csvFileScannedPath: FileScannedPath
  ): ZStream[Scope, ServiceError, CSVRecord]

}

object SpreadsheetTool {

  private final class SpreadsheetToolImpl extends SpreadsheetTool {
    inline private val csvTempFilePrefix       = "csv-"
    inline private val noCellsLastCellNumBound = 0
    inline private val firstCellIndex          = 0
    inline private val firstSheetIndex         = 0

    private def cellValues(row: Row, dataFormatter: DataFormatter): List[String] = {
      val lastCellNum = row.getLastCellNum
      if (lastCellNum < noCellsLastCellNumBound) List.empty
      else
        (firstCellIndex until lastCellNum).toList.map { cellIndex =>
          dataFormatter.formatCellValue(row.getCell(cellIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK))
        }
    }

    private def convertExcelToCsvFile(
        excelFileScannedPath: FileScannedPath
    ): ZIO[Scope, ServiceError, Path] =
      for {
        csvTempFile <- TempFile.createScoped(csvTempFilePrefix)
        _           <- ZIO.acquireReleaseWith(
          ZIO
            .attemptBlocking(WorkbookFactory.create(excelFileScannedPath.value.toFile))
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to open Excel workbook", Some(e)))
        )(workbook => ZIO.attemptBlocking(workbook.close()).ignoreLogged) { workbook =>
          ZIO.acquireReleaseWith(
            ZIO
              .attemptBlocking(
                new CSVPrinter(
                  new OutputStreamWriter(Files.newOutputStream(csvTempFile), StandardCharsets.UTF_8),
                  CSVFormat.DEFAULT,
                )
              )
              .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to open CSV writer", Some(e)))
          )(csvPrinter =>
            (ZIO.attemptBlocking(csvPrinter.flush()) *> ZIO.attemptBlocking(csvPrinter.close())).ignoreLogged
          ) { csvPrinter =>
            ZIO.attemptBlocking {
              val dataFormatter = new DataFormatter()
              val firstSheet    = workbook.getSheetAt(firstSheetIndex)
              firstSheet.rowIterator().asScala.foreach { row =>
                csvPrinter.printRecord(cellValues(row, dataFormatter)*)
              }
            }
              .mapError(e =>
                ServiceError.InternalServerError.UnexpectedError("Failed to convert Excel workbook to CSV", Some(e))
              )
          }
        }
      } yield csvTempFile

    private def csvRecordStream(csvPath: Path): ZStream[Scope, ServiceError, CSVRecord] =
      ZStream
        .acquireReleaseWith(
          ZIO
            .attemptBlocking(
              CSVParser.parse(Files.newBufferedReader(csvPath, StandardCharsets.UTF_8), CSVFormat.DEFAULT)
            )
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(e)))
        )(csvParser => ZIO.attemptBlocking(csvParser.close()).ignoreLogged)
        .flatMap { csvParser =>
          ZStream
            .blocking(ZStream.fromJavaIterator(csvParser.iterator()))
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(e)))
        }

    override def convertExcelToCsv(
        excelFileScannedPath: FileScannedPath
    ): ZStream[Scope, ServiceError, CSVRecord] =
      ZStream.unwrap(convertExcelToCsvFile(excelFileScannedPath).map(csvRecordStream))

    override def convertToCsv(
        csvFileScannedPath: FileScannedPath
    ): ZStream[Scope, ServiceError, CSVRecord] =
      csvRecordStream(csvFileScannedPath.value)

  }

  val live = ZLayer.derive[SpreadsheetToolImpl].project[SpreadsheetTool](identity)
}
