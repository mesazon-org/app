package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.utils.FileScanner.SupportedTypes
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

class FileScannerSpec extends ZWordSpecBase {

  private def resourceBytes(name: String): Chunk[Byte] =
    ZStream.fromResource(name).runCollect.zioValue

  private val fileScanner = ZIO
    .service[FileScanner]
    .provide(FileScanner.live)
    .zioValue

  private val fiveMb = 5 * 1024 * 1024L

  "FileScanner" when {
    "scan" should {
      "return a stream of the file bytes when it is a supported type within the size limit" in {
        val bytes = ZIO
          .scoped(
            fileScanner
              .scan(ZStream.fromResource("assets/test-logo-1.jpeg"), SupportedTypes.images, fiveMb)
              .flatMap(_.runCollect)
          )
          .zioValue

        bytes.toArray shouldBe resourceBytes("assets/test-logo-1.jpeg").toArray
      }

      "fail when the file size exceeds the maximum allowed" in {
        val oneKb = 1024L

        val result = ZIO
          .scoped(fileScanner.scan(ZStream.fromResource("assets/test-logo-1.jpeg"), SupportedTypes.images, oneKb))
          .zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [1024 bytes]"
        )
      }

      "fail when is one extra than the maximum allowed size" in {
        val file    = ZStream.fromResource("assets/test-logo-1.jpeg")
        val size    = file.runCount.zioValue
        val maxSize = size - 1

        val result = ZIO
          .scoped(fileScanner.scan(ZStream.fromResource("assets/test-logo-1.jpeg"), SupportedTypes.images, maxSize))
          .zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [49800 bytes]"
        )
      }

      "fail with not supported type when an unsupported file is provided" in {
        val result = ZIO
          .scoped(fileScanner.scan(ZStream.fromResource("docker-compose/s3.yaml"), SupportedTypes.images, fiveMb))
          .zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported types are: [image/png, image/jpeg, image/webp]"
        )
      }

      "fail with not supported type when the file extension is changed but content is not an image" in {
        val result = ZIO
          .scoped(fileScanner.scan(ZStream.fromResource("assets/malformed.png"), SupportedTypes.images, fiveMb))
          .zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported types are: [image/png, image/jpeg, image/webp]"
        )
      }

      "validate the size before the type when both are invalid" in {
        val oneByte = 1L

        val result = ZIO
          .scoped(fileScanner.scan(ZStream.fromResource("docker-compose/s3.yaml"), SupportedTypes.images, oneByte))
          .zioEither

        result.left.value shouldBe ServiceError.InternalServerError.UnexpectedError(
          "File size exceeds the maximum allowed size of [1 bytes]"
        )
      }
    }
  }
}
