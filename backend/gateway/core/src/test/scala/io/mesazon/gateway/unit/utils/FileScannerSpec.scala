package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.utils.FileScanner.SupportedTypes
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

class FileScannerSpec extends ZWordSpecBase {

  "FileScanner" when {
    "scan" should {
      "successfully scan a file if it's valid" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val organizationLogoBytes = ZStream.fromResource("test-logo-1.jpeg")
        val fiveMb                = 5 * 1024 * 1024L // 5 MB in bytes

        val result = fileScanner.scan(organizationLogoBytes, SupportedTypes.images, fiveMb).zioEither

        assert(result.isRight)
      }

      "fail with not supported type when not detected support file is provided" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val malformedBytes = ZStream.fromResource("s3.yaml")
        val fiveMb         = 5 * 1024 * 1024L // 5 MB in bytes

        val result = fileScanner.scan(malformedBytes, SupportedTypes.images, fiveMb).zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported types are: [image/png, image/jpeg, image/svg+xml, image/webp]"
        )
      }

      "fail with not supported type when not detected support file is provided with ending being changed" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val malformedBytes = ZStream.fromResource("text.png")
        val fiveMb         = 5 * 1024 * 1024L // 5 MB in bytes

        val result = fileScanner.scan(malformedBytes, SupportedTypes.images, fiveMb).zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported types are: [image/png, image/jpeg, image/svg+xml, image/webp]"
        )
      }

      "fail with not supported file size when file size exceeds the max allowed" in {
        val fileScanner = ZIO
          .service[FileScanner]
          .provide(FileScanner.live)
          .zioValue

        val malformedBytes = ZStream.fromResource("test-logo-1.jpeg")
        val oneKb          = 1024L // 1 KB in bytes

        val result = fileScanner.scan(malformedBytes, SupportedTypes.images, oneKb).zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size [49801 bytes] exceeds the maximum allowed size of [1024 bytes]"
        )
      }
    }
  }
}
