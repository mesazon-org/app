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
    "scanV1" should {
      "return the complete scanned output for every supported file whose declarations match after normalization" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        final case class ScanV1SuccessCase(
            fileResourcePath: String,
            fileNameDeclared: FileNameDeclared,
            supportedMediaTypeExpected: SupportedMediaType,
        )

        val scanV1SuccessCases = List(
          ScanV1SuccessCase(
            fileResourcePath = "assets/test-logo-3.png",
            fileNameDeclared = FileNameDeclared.assume("contact-book.PNG"),
            supportedMediaTypeExpected = SupportedMediaType.PNG,
          ),
          ScanV1SuccessCase(
            fileResourcePath = "assets/test-logo-1.jpeg",
            fileNameDeclared = FileNameDeclared.assume("contact-book.JPG"),
            supportedMediaTypeExpected = SupportedMediaType.JPEG,
          ),
          ScanV1SuccessCase(
            fileResourcePath = "assets/test-logo-1.jpeg",
            fileNameDeclared = FileNameDeclared.assume("contact-book.jpeg"),
            supportedMediaTypeExpected = SupportedMediaType.JPEG,
          ),
          ScanV1SuccessCase(
            fileResourcePath = "assets/test-logo-2.webp",
            fileNameDeclared = FileNameDeclared.assume("contact-book.WEBP"),
            supportedMediaTypeExpected = SupportedMediaType.WEBP,
          ),
          ScanV1SuccessCase(
            fileResourcePath = "assets/contact-book-test-spreadsheet-1.csv",
            fileNameDeclared = FileNameDeclared.assume("customers.CSV"),
            supportedMediaTypeExpected = SupportedMediaType.CSV,
          ),
          ScanV1SuccessCase(
            fileResourcePath = "assets/test-customers.xls",
            fileNameDeclared = FileNameDeclared.assume("customers.XLS"),
            supportedMediaTypeExpected = SupportedMediaType.XLS,
          ),
          ScanV1SuccessCase(
            fileResourcePath = "assets/test-customers.xlsx",
            fileNameDeclared = FileNameDeclared.assume("customers.XLSX"),
            supportedMediaTypeExpected = SupportedMediaType.XLSX,
          ),
        )
        val maxByteSize5Mb      = 5 * 1024 * 1024L
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        scanV1SuccessCases.foreach { scanV1SuccessCase =>
          withClue(s"scanV1 success case [$scanV1SuccessCase]") {
            val fileByteStream        = ZStream.fromResource(scanV1SuccessCase.fileResourcePath)
            val fileBytesSizeExpected = FileBytesSize.assume(fileByteStream.runCount.zioValue)

            val scanTestResult = ZIO
              .scoped(
                fileScanner
                  .scanV1(
                    fileByteStream,
                    scanV1SuccessCase.fileNameDeclared,
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

            scanTestResult.supportedMediaType shouldBe scanV1SuccessCase.supportedMediaTypeExpected
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
        val fileByteStream      = ZStream.fromResource("assets/test-logo-1.jpeg")
        val fileNameDeclared    = FileNameDeclared.assume("photo")
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
        val fileNameDeclared    = FileNameDeclared.assume("photo.gif")
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
        val fileByteStream      = ZStream.fromResource("assets/test-logo-1.jpeg")
        val fileNameDeclared    = FileNameDeclared.assume("photo.png")
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
        val fileByteStream      = ZStream.fromResource("assets/test-customers.xlsx")
        val fileNameDeclared    = FileNameDeclared.assume("customers.csv")
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
        val fileNameDeclared    = FileNameDeclared.assume("document.png")
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
        val fileNameDeclared    = FileNameDeclared.assume("photo.jpeg")
        val supportedMediaTypes = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
        val fileByteStreamExpected = ZStream.fromResource("assets/test-logo-1.jpeg")
        val fileByteStream         = fileByteStreamExpected.tap(_ => bytesReadRef.update(_ + bytesReadIncrement))
        val maxByteSize1b          = 1L
        val fileNameDeclared       = FileNameDeclared.assume("customers.csv")
        val supportedMediaTypes    = SupportedMediaType.images ++ SupportedMediaType.spreadsheets

        val serviceError = ZIO
          .scoped(
            fileScanner.scanV1(
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
