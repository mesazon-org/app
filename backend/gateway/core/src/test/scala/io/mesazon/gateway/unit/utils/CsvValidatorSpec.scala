package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.{CsvValidator, FileByteStreamScanned}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

class CsvValidatorSpec extends ZWordSpecBase {

  private val wellFormedCsvText =
    "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\nJane Doe,jane.doe@example.com\r\n"

  private val malformedCsvText =
    "Full Name,Email\r\n\"John Smith,john.smith@example.com\r\n"

  "CsvValidator" when {
    "validate" should {
      "accept a well-formed CSV file" in {
        val csvValidator = ZIO
          .service[CsvValidator]
          .provide(CsvValidator.live)
          .zioValue

        val csvByteStream =
          FileByteStreamScanned(ZStream.fromIterable(wellFormedCsvText.getBytes(StandardCharsets.UTF_8)))

        ZIO.scoped(csvValidator.validate(csvByteStream)).zioValue shouldBe ()
      }

      "fail with UnexpectedError when the content is not structurally valid CSV" in {
        val csvValidator = ZIO
          .service[CsvValidator]
          .provide(CsvValidator.live)
          .zioValue

        val csvByteStream =
          FileByteStreamScanned(ZStream.fromIterable(malformedCsvText.getBytes(StandardCharsets.UTF_8)))

        val serviceError = ZIO.scoped(csvValidator.validate(csvByteStream)).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }
    }
  }
}
