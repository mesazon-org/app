package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.{FileBytesSize, ServiceError, SupportedMediaType}
import io.mesazon.gateway.utils.FileScanner
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

class FileScannerSpec extends ZWordSpecBase {

  "FileScanner" when {
    "scan" should {
      "return a stream of the file bytes, the detected mime type, and the file size when it is a supported type within the size limit" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb        = 5 * 1024 * 1024L
        val fileByteStream        = ZStream.fromResource("assets/test-logo-1.jpeg")
        val fileBytesSizeExpected = FileBytesSize.assume(fileByteStream.runCount.zioValue)

        val scanTestResult = ZIO
          .scoped(
            fileScanner
              .scan(fileByteStream, SupportedMediaType.images, maxByteSize5Mb)
              .flatMap { fileScannerScanOutput =>
                fileScannerScanOutput.fileByteStreamScanned.value.runCollect
                  .map(scannedFileBytes =>
                    (
                      supportedMediaType = fileScannerScanOutput.supportedMediaType,
                      fileBytesSize = fileScannerScanOutput.fileBytesSize,
                      scannedFileBytes = scannedFileBytes,
                    )
                  )
              }
          )
          .zioValue

        scanTestResult.supportedMediaType shouldBe SupportedMediaType.JPEG
        scanTestResult.fileBytesSize shouldBe fileBytesSizeExpected
        scanTestResult.scannedFileBytes shouldBe fileByteStream.runCollect.zioValue
      }

      "correctly detect a genuine CSV file via magic-only detection, with no hinted retry needed" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb = 5 * 1024 * 1024L
        val csvText        = "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\nJane Doe,jane.doe@example.com\r\n"
        val fileByteStream = ZStream.fromIterable(csvText.getBytes(StandardCharsets.UTF_8))

        val supportedMediaType = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.spreadsheets, maxByteSize5Mb))
          .zioValue
          .supportedMediaType

        supportedMediaType shouldBe SupportedMediaType.PLAINTEXT_CSV
      }

      "correctly detect a genuine .xlsx file with no real file name to hint with" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb = 5 * 1024 * 1024L
        val fileByteStream = ZStream.fromResource("assets/test-customers.xlsx")

        val supportedMediaType = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.spreadsheets, maxByteSize5Mb))
          .zioValue
          .supportedMediaType

        supportedMediaType shouldBe SupportedMediaType.XLSX
      }

      "correctly detect a genuine legacy .xls file with no real file name to hint with" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb = 5 * 1024 * 1024L
        val fileByteStream = ZStream.fromResource("assets/test-customers.xls")

        val supportedMediaType = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.spreadsheets, maxByteSize5Mb))
          .zioValue
          .supportedMediaType

        supportedMediaType shouldBe SupportedMediaType.XLS
      }

      "fail when the file size exceeds the maximum bytes allowed" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize1kb = 1024L
        val fileByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val serviceError = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.images, maxByteSize1kb))
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [1024 bytes]"
        )
      }

      "fail when is one extra than the maximum bytes allowed size" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val fileByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")
        val maxByteSize    = fileByteStream.runCount.zioValue - 1

        val serviceError = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.images, maxByteSize))
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [49800 bytes]"
        )
      }

      "fail with not supported type when an unsupported file is provided" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb = 5 * 1024 * 1024L
        val fileByteStream = ZStream.fromResource("compose/s3.yaml")

        val serviceError = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.images, maxByteSize5Mb))
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported file types are: [image/png, image/jpeg, image/webp]"
        )
      }

      "fail with not supported type when the file extension is changed but content is not an image" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb = 5 * 1024 * 1024L
        val fileByteStream = ZStream.fromResource("assets/malformed.png")

        val serviceError = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.images, maxByteSize5Mb))
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported file types are: [image/png, image/jpeg, image/webp]"
        )
      }

      "validate the size before the type when both are invalid" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize1b  = 1L
        val fileByteStream = ZStream.fromResource("compose/s3.yaml")

        val serviceError = ZIO
          .scoped(fileScanner.scan(fileByteStream, SupportedMediaType.images, maxByteSize1b))
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [1 bytes]"
        )
      }
    }
  }
}
