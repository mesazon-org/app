package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.ImageProcessing
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.file.{Files, Paths}

class ImageProcessingSpec extends ZWordSpecBase {

  private val goldenDir = Paths.get("src/test/resources/golden-assets")

  private def createOrGetGolden(fileName: String, actual: Array[Byte]): Array[Byte] = {
    val golden = goldenDir.resolve(fileName)
    if (!Files.exists(golden)) Files.write(golden, actual)
    Files.readAllBytes(golden)
  }

  private def resourceBytes(name: String): Array[Byte] =
    ZStream.fromResource(name).runCollect.zioValue.toArray

  private val imageProcessing = ZIO
    .service[ImageProcessing]
    .provide(ImageProcessing.live)
    .zioValue

  private def verifyRenditions(upload: String, normalizedGolden: String, whatsAppGolden: String): Unit = {
    val (originalLogo, normalizedLogo, whatsAppLogo) = ZIO
      .scoped(
        imageProcessing.processLogo(ZStream.fromResource(upload)).flatMap { processed =>
          for {
            originalLogo     <- processed.originalLogo.runCollect
            normalizedLogo   <- processed.normalizedLogo.runCollect
            whatsAppLogo <- processed.whatsAppLogo.runCollect
          } yield (originalLogo.toArray, normalizedLogo.toArray, whatsAppLogo.toArray)
        }
      )
      .zioValue

    originalLogo shouldBe resourceBytes(upload)
    normalizedLogo shouldBe createOrGetGolden(normalizedGolden, normalizedLogo)
    whatsAppLogo shouldBe createOrGetGolden(whatsAppGolden, whatsAppLogo)
  }

  "ImageProcessing" when {
    "processLogo" should {
      "derive the normalized WebP and WhatsApp JPEG icon from a JPEG upload matching the golden bytes" in {
        verifyRenditions("assets/test-logo-1.jpeg", "normalized-logo-1.webp", "whatsapp-logo-1.jpeg")
      }

      "derive the normalized WebP and WhatsApp JPEG icon from a WebP upload matching the golden bytes" in {
        verifyRenditions("assets/test-logo-2.webp", "normalized-logo-2.webp", "whatsapp-logo-2.jpeg")
      }

      "derive the normalized WebP and WhatsApp JPEG icon from a PNG upload matching the golden bytes" in {
        verifyRenditions("assets/test-logo-3.png", "normalized-logo-3.webp", "whatsapp-logo-3.jpeg")
      }

      "fail to decode when the bytes are not a real image" in {
        val result = ZIO.scoped(imageProcessing.processLogo(ZStream.fromResource("assets/malformed.png"))).zioEither

        result.left.value shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        result.left.value.asInstanceOf[ServiceError.InternalServerError.UnexpectedError].error shouldBe
          "Failed to decode organization logo"
      }
    }
  }
}
