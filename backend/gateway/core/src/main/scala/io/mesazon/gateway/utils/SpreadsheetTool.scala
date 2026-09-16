package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.{ServiceError, SupportedMediaType}
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVPrinter}
import org.apache.poi.ss.usermodel.*
import zio.*
import zio.stream.*

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

trait SpreadsheetTool {
  def validateAndConvertToCsv(
      fileScannedPath: FileScannedPath,
      supportedMediaType: SupportedMediaType,
  ): ZIO[Scope, ServiceError, CsvValidatedPath]

  def convertValidateCsv(
      fileByteStreamScanned: FileByteStreamScanned,
      supportedMediaType: SupportedMediaType,
  ): ZIO[Scope, ServiceError, ValidatedCsvByteStream]
}

object SpreadsheetTool {

  private final class SpreadsheetToolImpl extends SpreadsheetTool {
    inline private val fileTempFilePrefix      = "file-"
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

    private def spoolToTempFile(fileByteStreamScanned: FileByteStreamScanned): ZIO[Scope, ServiceError, Path] =
      for {
        fileTempFile <- TempFile.createScoped(fileTempFilePrefix)
        _            <- fileByteStreamScanned.value
          .run(ZSink.fromPath(fileTempFile))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to write file to temp file", Some(e)))
      } yield fileTempFile

    private def validateCsvFile(csvTempFile: Path): ZIO[Scope, ServiceError, Unit] =
      ZIO
        .acquireReleaseWith(
          ZIO
            .attemptBlocking(
              CSVParser.parse(Files.newBufferedReader(csvTempFile, StandardCharsets.UTF_8), CSVFormat.DEFAULT)
            )
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(e)))
        )(csvParser => ZIO.attemptBlocking(csvParser.close()).ignoreLogged) { csvParser =>
          ZIO
            .attemptBlocking(csvParser.iterator().asScala.foreach(_ => ()))
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(e)))
        }
        .unit

    private def validateAndRefine(csvPath: Path): ZIO[Scope, ServiceError, CsvValidatedPath] =
      validateCsvFile(csvPath).as(CsvValidatedPath(csvPath))

    override def validateAndConvertToCsv(
        fileScannedPath: FileScannedPath,
        supportedMediaType: SupportedMediaType,
    ): ZIO[Scope, ServiceError, CsvValidatedPath] =
      supportedMediaType match {
        case mediaType if SupportedMediaType.excel.contains(mediaType) =>
          convertExcelToCsvFile(fileScannedPath).flatMap(validateAndRefine)
        case mediaType if SupportedMediaType.csv.contains(mediaType) =>
          validateAndRefine(fileScannedPath.value)
        case unexpected =>
          ZIO.fail(
            ServiceError.InternalServerError
              .UnexpectedError(s"Unsupported media type for CSV/Excel conversion: [$unexpected]")
          )
      }

    override def convertValidateCsv(
        fileByteStreamScanned: FileByteStreamScanned,
        supportedMediaType: SupportedMediaType,
    ): ZIO[Scope, ServiceError, ValidatedCsvByteStream] =
      spoolToTempFile(fileByteStreamScanned)
        .flatMap(fileTempFile => validateAndConvertToCsv(FileScannedPath(fileTempFile), supportedMediaType))
        .map(csvValidatedPath => ValidatedCsvByteStream(ZStream.fromPath(csvValidatedPath.value)))
  }

  val live = ZLayer.derive[SpreadsheetToolImpl].project[SpreadsheetTool](identity)
}
