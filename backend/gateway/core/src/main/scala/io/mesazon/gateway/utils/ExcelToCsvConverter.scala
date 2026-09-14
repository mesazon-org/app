package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import org.apache.commons.csv.*
import org.apache.poi.ss.usermodel.*
import zio.*
import zio.stream.*

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

trait ExcelToCsvConverter {
  def convert(excelByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, FileByteStreamScanned]
}

object ExcelToCsvConverter {

  private final class ExcelToCsvConverterImpl extends ExcelToCsvConverter {
    inline private val excelTempFilePrefix     = "excel-"
    inline private val csvTempFilePrefix       = "excel-csv-"
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

    override def convert(
        excelByteStreamScanned: FileByteStreamScanned
    ): ZIO[Scope, ServiceError, FileByteStreamScanned] =
      for {
        excelTempFile <- TempFile.createScoped(excelTempFilePrefix)
        _             <- excelByteStreamScanned.value
          .run(ZSink.fromPath(excelTempFile))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to write Excel file to temp file", Some(e))
          )
        csvTempFile <- TempFile.createScoped(csvTempFilePrefix)
        _           <- ZIO.acquireReleaseWith(
          ZIO
            .attemptBlocking(WorkbookFactory.create(excelTempFile.toFile))
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
      } yield FileByteStreamScanned(ZStream.fromPath(csvTempFile))
  }

  val live = ZLayer.derive[ExcelToCsvConverterImpl].project[ExcelToCsvConverter](identity)
}
