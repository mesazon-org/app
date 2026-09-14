package io.mesazon.gateway.unit.utils

import io.mesazon.gateway.utils.{ExcelToCsvConverter, FileByteStreamScanned}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

class ExcelToCsvConverterSpec extends ZWordSpecBase {

  // Both fixtures' single relevant sheet ("Customers") holds the same header and two data rows, so
  // both tests below assert the exact same CSV-shaped text once converted.
  private val expectedCustomersCsvText =
    "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\nJane Doe,jane.doe@example.com\r\n"

  "ExcelToCsvConverter" when {
    "convert" should {
      "convert only the first sheet of a multi-sheet .xlsx workbook to CSV-shaped text, ignoring the other sheets" in {
        val excelToCsvConverter = ZIO
          .service[ExcelToCsvConverter]
          .provide(ExcelToCsvConverter.live)
          .zioValue

        val excelByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-customers.xlsx"))

        val convertedCsvText = ZIO
          .scoped(
            excelToCsvConverter
              .convert(excelByteStream)
              .flatMap(_.value.runCollect)
          )
          .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
          .zioValue

        convertedCsvText shouldBe expectedCustomersCsvText
      }

      "convert a legacy .xls workbook to the same CSV-shaped text as an equivalent .xlsx workbook" in {
        val excelToCsvConverter = ZIO
          .service[ExcelToCsvConverter]
          .provide(ExcelToCsvConverter.live)
          .zioValue

        val excelByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-customers.xls"))

        val convertedCsvText = ZIO
          .scoped(
            excelToCsvConverter
              .convert(excelByteStream)
              .flatMap(_.value.runCollect)
          )
          .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
          .zioValue

        convertedCsvText shouldBe expectedCustomersCsvText
      }
    }
  }
}
