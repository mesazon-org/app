package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.*

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

class SpreadsheetToolSpec extends ZWordSpecBase {

  private inline val fileTempPrefix = "spreadsheet-tool-spec-"

  private val wellFormedCsvText =
    "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\nJane Doe,jane.doe@example.com\r\n"

  private val malformedCsvText =
    "Full Name,Email\r\n\"John Smith,john.smith@example.com\r\n"

  private val wellFormedCsvRecordValues = List(
    List("Full Name", "Email"),
    List("John Smith", "john.smith@example.com"),
    List("Jane Doe", "jane.doe@example.com"),
  )

  private def fileScannedPath(
      fileByteStream: ZStream[Any, Throwable, Byte]
  ): ZIO[Scope, ServiceError, FileScannedPath] =
    for {
      fileTempPath <- TempFile.createScoped(fileTempPrefix)
      _            <- fileByteStream.run(ZSink.fromPath(fileTempPath)).orDie
    } yield FileScannedPath(fileTempPath)

  "SpreadsheetTool" when {
    "convertExcelToCsv" should {
      "return the parsed records of only the first sheet of a multi-sheet .xlsx workbook, header row included, ignoring the other sheets" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val csvRecordValues = ZIO
          .scoped(for {
            excelFileScannedPath <- fileScannedPath(
              ZStream.fromResource("assets-test/SpreadsheetToolSpec/spreadsheet-1.xlsx")
            )
            csvRecords <- spreadsheetTool.convertExcelToCsv(excelFileScannedPath).runCollect
          } yield csvRecords.toList.map(_.asScala.toList))
          .zioValue

        csvRecordValues shouldBe wellFormedCsvRecordValues
      }

      "return the same parsed records for a legacy .xls workbook as an equivalent .xlsx workbook" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val csvRecordValues = ZIO
          .scoped(for {
            excelFileScannedPath <- fileScannedPath(
              ZStream.fromResource("assets-test/SpreadsheetToolSpec/spreadsheet-2.xls")
            )
            csvRecords <- spreadsheetTool.convertExcelToCsv(excelFileScannedPath).runCollect
          } yield csvRecords.toList.map(_.asScala.toList))
          .zioValue

        csvRecordValues shouldBe wellFormedCsvRecordValues
      }

      "leave the .xlsx workbook file unchanged after converting it" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val (excelFileBytesBefore, excelFileBytesAfter, csvRecordValues) = ZIO
          .scoped(for {
            excelFileScannedPath <- fileScannedPath(
              ZStream.fromResource("assets-test/SpreadsheetToolSpec/spreadsheet-1.xlsx")
            )
            excelFileBytesBefore <- ZStream.fromPath(excelFileScannedPath.value).runCollect.orDie
            csvRecords           <- spreadsheetTool.convertExcelToCsv(excelFileScannedPath).runCollect
            excelFileBytesAfter  <- ZStream.fromPath(excelFileScannedPath.value).runCollect.orDie
          } yield (excelFileBytesBefore, excelFileBytesAfter, csvRecords.toList.map(_.asScala.toList)))
          .zioValue

        excelFileBytesAfter shouldBe excelFileBytesBefore

        csvRecordValues shouldBe wellFormedCsvRecordValues
      }

      // Apache POI's HSSF (.xls) never writes back on close, so this cannot fail today: unlike the .xlsx case above,
      // it is a guard against a later change to how the workbook is opened or closed writing to the uploaded file.
      "leave the .xls workbook file unchanged after converting it" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val (excelFileBytesBefore, excelFileBytesAfter, csvRecordValues) = ZIO
          .scoped(for {
            excelFileScannedPath <- fileScannedPath(
              ZStream.fromResource("assets-test/SpreadsheetToolSpec/spreadsheet-2.xls")
            )
            excelFileBytesBefore <- ZStream.fromPath(excelFileScannedPath.value).runCollect.orDie
            csvRecords           <- spreadsheetTool.convertExcelToCsv(excelFileScannedPath).runCollect
            excelFileBytesAfter  <- ZStream.fromPath(excelFileScannedPath.value).runCollect.orDie
          } yield (excelFileBytesBefore, excelFileBytesAfter, csvRecords.toList.map(_.asScala.toList)))
          .zioValue

        excelFileBytesAfter shouldBe excelFileBytesBefore

        csvRecordValues shouldBe wellFormedCsvRecordValues
      }
    }

    "convertToCsv" should {
      "return the parsed records of a well-formed CSV file without converting it, header row included" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val csvRecordValues = ZIO
          .scoped(for {
            csvFileScannedPath <- fileScannedPath(
              ZStream.fromIterable(wellFormedCsvText.getBytes(StandardCharsets.UTF_8))
            )
            csvRecords <- spreadsheetTool.convertToCsv(csvFileScannedPath).runCollect
          } yield csvRecords.toList.map(_.asScala.toList))
          .zioValue

        csvRecordValues shouldBe wellFormedCsvRecordValues
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
            csvRecords <- spreadsheetTool.convertToCsv(csvFileScannedPath).runCollect
          } yield csvRecords)
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }
    }
  }
}
