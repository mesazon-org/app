package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.{ServiceError, SupportedMediaTypes}
import io.mesazon.gateway.utils.{FileByteStreamScanned, ImageProcessing}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.file.{Files, Paths}

class ImageProcessingSpec extends ZWordSpecBase {

  private val goldenDir = Paths.get("src/test/resources/golden-assets")

  private def createOrGetGolden(fileName: String, actual: Chunk[Byte]): Chunk[Byte] = {
    val golden = goldenDir.resolve(fileName)

    val _ = if (!Files.exists(golden)) Files.write(golden, actual.toArray) else ()

    Chunk.fromArray(Files.readAllBytes(golden))
  }

  "ImageProcessing" when {
    "normalize" should {
      "return a successful result when a JPEG logo is uploaded" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val logoOriginalByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        ZIO
          .scoped(for {
            normalizeResult <- imageProcessing.normalize(
              FileByteStreamScanned(logoOriginalByteStream),
              SupportedMediaTypes.images,
            )
          } yield {
            val logoOriginalBytes   = normalizeResult.imageOriginalByteStream.value.runCollect.zioValue
            val logoNormalizedBytes = normalizeResult.imageNormalizedByteStream.value.runCollect.zioValue

            logoOriginalBytes shouldBe logoOriginalByteStream.runCollect.zioValue
            logoNormalizedBytes shouldBe createOrGetGolden("normalized-logo-1.webp", logoNormalizedBytes)
          })
          .zioValue
      }

      "return a successful result when a WebP logo is uploaded" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val logoOriginalByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        ZIO
          .scoped(for {
            normalizeResult <- imageProcessing.normalize(
              FileByteStreamScanned(logoOriginalByteStream),
              SupportedMediaTypes.images,
            )
          } yield {
            val logoOriginalBytes   = normalizeResult.imageOriginalByteStream.value.runCollect.zioValue
            val logoNormalizedBytes = normalizeResult.imageNormalizedByteStream.value.runCollect.zioValue

            logoOriginalBytes shouldBe logoOriginalByteStream.runCollect.zioValue
            logoNormalizedBytes shouldBe createOrGetGolden("normalized-logo-2.webp", logoNormalizedBytes)
          })
          .zioValue
      }

      "return a successful result when a PNG logo is uploaded" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val logoOriginalByteStream = ZStream.fromResource("assets/test-logo-3.png")

        ZIO
          .scoped(for {
            normalizeResult <- imageProcessing.normalize(
              FileByteStreamScanned(logoOriginalByteStream),
              SupportedMediaTypes.images,
            )
          } yield {
            val logoOriginalBytes   = normalizeResult.imageOriginalByteStream.value.runCollect.zioValue
            val logoNormalizedBytes = normalizeResult.imageNormalizedByteStream.value.runCollect.zioValue

            logoOriginalBytes shouldBe logoOriginalByteStream.runCollect.zioValue
            logoNormalizedBytes shouldBe createOrGetGolden("normalized-logo-3.webp", logoNormalizedBytes)
          })
          .zioValue
      }

      "fail when an unsupported file is uploaded" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val result = ZIO
          .scoped(
            imageProcessing.normalize(
              FileByteStreamScanned(ZStream.fromResource("assets/malformed.png")),
              SupportedMediaTypes.images,
            )
          )
          .zioEither

        result.left.value shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        result.left.value.asInstanceOf[ServiceError.InternalServerError.UnexpectedError].error shouldBe
          "Unsupported organization logo format"
      }
    }
  }
}
