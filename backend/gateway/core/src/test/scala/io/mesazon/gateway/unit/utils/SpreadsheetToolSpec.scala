package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SpreadsheetToolSpec extends ZWordSpecBase {

  inline private val fileTempPrefix = "spreadsheet-tool-spec-"

  private val wellFormedCsvText =
    "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\nJane Doe,jane.doe@example.com\r\n"

  private val malformedCsvText =
    "Full Name,Email\r\n\"John Smith,john.smith@example.com\r\n"

  private def fileScannedPath(
      fileByteStream: ZStream[Any, Throwable, Byte]
  ): ZIO[Scope, ServiceError, FileScannedPath] =
    for {
      fileTempPath <- TempFile.createScoped(fileTempPrefix)
      _            <- fileByteStream.run(ZSink.fromPath(fileTempPath)).orDie
    } yield FileScannedPath(fileTempPath)

  "SpreadsheetTool" when {
    "convertExcelToCsv" should {
      "convert only the first sheet of a multi-sheet .xlsx workbook to validated CSV-shaped text, ignoring the other sheets" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val convertedCsvText = ZIO
          .scoped(for {
            excelFileScannedPath <- fileScannedPath(ZStream.fromResource("assets/contact-book-test-spreadsheet-3.xlsx"))
            csvValidatedPath     <- spreadsheetTool.convertExcelToCsv(excelFileScannedPath)
            csvText              <- ZIO.attemptBlocking(Files.readString(csvValidatedPath.value)).orDie
          } yield csvText)
          .zioValue

        convertedCsvText shouldBe wellFormedCsvText
      }

      "convert a legacy .xls workbook to the same validated CSV-shaped text as an equivalent .xlsx workbook" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val convertedCsvText = ZIO
          .scoped(for {
            excelFileScannedPath <- fileScannedPath(ZStream.fromResource("assets/contact-book-test-spreadsheet-2.xls"))
            csvValidatedPath     <- spreadsheetTool.convertExcelToCsv(excelFileScannedPath)
            csvText              <- ZIO.attemptBlocking(Files.readString(csvValidatedPath.value)).orDie
          } yield csvText)
          .zioValue

        convertedCsvText shouldBe wellFormedCsvText
      }
    }

    "convertToCsv" should {
      "accept a well-formed CSV file without converting it" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val validatedCsvText = ZIO
          .scoped(for {
            csvFileScannedPath <- fileScannedPath(
              ZStream.fromIterable(wellFormedCsvText.getBytes(StandardCharsets.UTF_8))
            )
            csvValidatedPath <- spreadsheetTool.convertToCsv(csvFileScannedPath)
            csvText          <- ZIO.attemptBlocking(Files.readString(csvValidatedPath.value)).orDie
          } yield csvText)
          .zioValue

        validatedCsvText shouldBe wellFormedCsvText
      }

      "fail with UnexpectedError when the CSV content is not structurally valid CSV" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val serviceError = ZIO
          .scoped(for {
            csvFileScannedPath <- fileScannedPath(
              ZStream.fromIterable(malformedCsvText.getBytes(StandardCharsets.UTF_8))
            )
            validatedCsvByteStream <- spreadsheetTool.convertToCsv(csvFileScannedPath)
          } yield validatedCsvByteStream)
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }
    }
  }
}
