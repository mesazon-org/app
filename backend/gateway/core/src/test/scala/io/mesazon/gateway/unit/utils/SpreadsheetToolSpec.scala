package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.{ServiceError, SupportedMediaType}
import io.mesazon.gateway.utils.{FileByteStreamScanned, SpreadsheetTool}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

class SpreadsheetToolSpec extends ZWordSpecBase {

  private val wellFormedCsvText =
    "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\nJane Doe,jane.doe@example.com\r\n"

  private val malformedCsvText =
    "Full Name,Email\r\n\"John Smith,john.smith@example.com\r\n"

  "SpreadsheetTool" when {
    "convertValidateCsv" should {
      "convert only the first sheet of a multi-sheet .xlsx workbook to validated CSV-shaped text, ignoring the other sheets" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val excelByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-customers.xlsx"))

        val convertedCsvText = ZIO
          .scoped(
            spreadsheetTool
              .convertValidateCsv(excelByteStream, SupportedMediaType.XLSX)
              .flatMap(_.value.runCollect)
          )
          .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
          .zioValue

        convertedCsvText shouldBe wellFormedCsvText
      }

      "convert a legacy .xls workbook to the same validated CSV-shaped text as an equivalent .xlsx workbook" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val excelByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-customers.xls"))

        val convertedCsvText = ZIO
          .scoped(
            spreadsheetTool
              .convertValidateCsv(excelByteStream, SupportedMediaType.XLS)
              .flatMap(_.value.runCollect)
          )
          .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
          .zioValue

        convertedCsvText shouldBe wellFormedCsvText
      }

      "accept a well-formed CSV file without converting it" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val csvByteStream =
          FileByteStreamScanned(ZStream.fromIterable(wellFormedCsvText.getBytes(StandardCharsets.UTF_8)))

        val validatedCsvText = ZIO
          .scoped(
            spreadsheetTool
              .convertValidateCsv(csvByteStream, SupportedMediaType.CSV)
              .flatMap(_.value.runCollect)
          )
          .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
          .zioValue

        validatedCsvText shouldBe wellFormedCsvText
      }

      "fail with UnexpectedError when the CSV content is not structurally valid CSV" in {
        val spreadsheetTool = ZIO
          .service[SpreadsheetTool]
          .provide(SpreadsheetTool.live)
          .zioValue

        val csvByteStream =
          FileByteStreamScanned(ZStream.fromIterable(malformedCsvText.getBytes(StandardCharsets.UTF_8)))

        val serviceError = ZIO
          .scoped(spreadsheetTool.convertValidateCsv(csvByteStream, SupportedMediaType.CSV))
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }
    }
  }
}
