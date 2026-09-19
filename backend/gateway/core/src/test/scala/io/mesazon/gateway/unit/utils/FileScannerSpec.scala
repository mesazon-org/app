package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.utils.FileScanner
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FileScannerSpec extends ZWordSpecBase {

  "FileScanner" when {
    "scan" should {
      "return the complete scanned output for every supported file whose declarations match after normalization" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        final case class ScanSuccessCase(
            fileResourcePath: String,
            fileNameDeclared: String,
            supportedMediaTypeExpected: SupportedMediaType,
        )

        val scanSuccessCases = List(
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-image-test-13.png",
            fileNameDeclared = "contact-book.PNG",
            supportedMediaTypeExpected = SupportedMediaType.PNG,
          ),
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-image-test-11.jpeg",
            fileNameDeclared = "contact-book.JPG",
            supportedMediaTypeExpected = SupportedMediaType.JPEG,
          ),
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-image-test-11.jpeg",
            fileNameDeclared = "contact-book.jpeg",
            supportedMediaTypeExpected = SupportedMediaType.JPEG,
          ),
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-image-test-12.webp",
            fileNameDeclared = "contact-book.WEBP",
            supportedMediaTypeExpected = SupportedMediaType.WEBP,
          ),
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-spreadsheet-test-1.csv",
            fileNameDeclared = "customers.CSV",
            supportedMediaTypeExpected = SupportedMediaType.CSV,
          ),
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-spreadsheet-test-2.xls",
            fileNameDeclared = "customers.XLS",
            supportedMediaTypeExpected = SupportedMediaType.XLS,
          ),
          ScanSuccessCase(
            fileResourcePath = "assets/contact-book-spreadsheet-test-3.xlsx",
            fileNameDeclared = "customers.XLSX",
            supportedMediaTypeExpected = SupportedMediaType.XLSX,
          ),
        )
        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        scanSuccessCases.foreach { scanSuccessCase =>
          withClue(s"scan success case [$scanSuccessCase]") {
            val fileByteStream        = ZStream.fromResource(scanSuccessCase.fileResourcePath)
            val fileBytesSizeExpected = FileBytesSize.assume(fileByteStream.runCount.zioValue)

            val scanTestResult = ZIO
              .scoped(
                fileScanner
                  .scan(
                    fileByteStream,
                    scanSuccessCase.fileNameDeclared,
                    supportedMediaTypes,
                    maxByteSize5Mb,
                  )
                  .flatMap { fileScannerScanOutput =>
                    ZIO
                      .attemptBlocking(Files.readAllBytes(fileScannerScanOutput.fileScannedPath.value))
                      .map(scannedFileBytes =>
                        (
                          supportedMediaType = fileScannerScanOutput.supportedMediaType,
                          fileBytesSize = fileScannerScanOutput.fileBytesSize,
                          scannedFileBytes = Chunk.fromArray(scannedFileBytes),
                        )
                      )
                  }
              )
              .zioValue

            scanTestResult.supportedMediaType shouldBe scanSuccessCase.supportedMediaTypeExpected
            scanTestResult.fileBytesSize shouldBe fileBytesSizeExpected
            scanTestResult.scannedFileBytes shouldBe fileByteStream.runCollect.zioValue
          }
        }
      }

      "fail with a ValidationError when the filename declaration has no extension" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val fileByteStream      = ZStream.fromResource("assets/contact-book-image-test-11.jpeg")
        val fileNameDeclared    = "image"
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize5Mb,
            )
          )
          .zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq("fileNameDeclared")
      }

      "fail with a ValidationError when the filename declaration has an unsupported extension" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val fileByteStream      = ZStream.fail(new RuntimeException("The upload stream must not be read"))
        val fileNameDeclared    = "image.gif"
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize5Mb,
            )
          )
          .zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq("fileNameDeclared")
      }

      "fail with a ValidationError when the filename extension declaration disagrees with the detected media type" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val fileByteStream      = ZStream.fromResource("assets/contact-book-image-test-11.jpeg")
        val fileNameDeclared    = "image.png"
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize5Mb,
            )
          )
          .zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq("fileNameDeclared")
      }

      "fail with a ValidationError when a spreadsheet filename extension disagrees with the detected format" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val fileByteStream      = ZStream.fromResource("assets/contact-book-spreadsheet-test-3.xlsx")
        val fileNameDeclared    = "customers.csv"
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize5Mb,
            )
          )
          .zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq("fileNameDeclared")
      }

      "fail with a ValidationError when the actual content does not match the declared supported type" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val pdfText             = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF"
        val fileByteStream      = ZStream.fromIterable(pdfText.getBytes(StandardCharsets.UTF_8))
        val fileNameDeclared    = "document.png"
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize5Mb,
            )
          )
          .zioError

        serviceError shouldBe a[ServiceError.BadRequestError.ValidationError]
        serviceError
          .asInstanceOf[ServiceError.BadRequestError.ValidationError]
          .invalidFields
          .map(_.fieldName) shouldBe Seq("fileNameDeclared")
      }

      "fail with an InternalServerError when the incoming file stream cannot be read" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val fileReadError       = new RuntimeException("Failed while reading the upload")
        val fileByteStream      = ZStream.fail(fileReadError)
        val fileNameDeclared    = "image.jpeg"
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize5Mb,
            )
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Failed to write file to temp file",
          Some(fileReadError),
        )
      }

      "fail with an InternalServerError after fully draining an oversized stream" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val bytesReadInitial       = 0L
        val bytesReadIncrement     = 1L
        val bytesReadRef           = Ref.make(bytesReadInitial).zioValue
        val fileByteStreamExpected = ZStream.fromResource("assets/contact-book-image-test-11.jpeg")
        val fileByteStream         = fileByteStreamExpected.tap(_ => bytesReadRef.update(_ + bytesReadIncrement))
        val maxByteSize1b          = 1L
        val fileNameDeclared       = "customers.csv"
        val supportedMediaTypes    = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scan(
              fileByteStream,
              fileNameDeclared,
              supportedMediaTypes,
              maxByteSize1b,
            )
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [1 bytes]"
        )
        bytesReadRef.refValue shouldBe fileByteStreamExpected.runCount.zioValue
      }
    }
  }
}
