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

        supportedMediaType shouldBe SupportedMediaType.CSV
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
