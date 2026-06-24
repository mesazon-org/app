package io.mesazon.gateway.unit.utils

import io.mesazon.domain.gateway.{ServiceError, SupportedMediaTypes}
import io.mesazon.gateway.utils.ImageProcessing
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.file.{Files, Paths}

class ImageProcessingSpec extends ZWordSpecBase {

  private val goldenDir = Paths.get("src/test/resources/golden-assets")

  private def createOrGetGolden(fileName: String, actual: Chunk[Byte]): Chunk[Byte] = {
    val golden = goldenDir.resolve(fileName)
    if (!Files.exists(golden)) Chunk.fromArray(Files.write(golden, actual.toArray))
    Chunk.fromArray(Files.readAllBytes(golden))
  }

  "ImageProcessing" when {
    "processLogo" should {
      "derive the normalized WebP and WhatsApp JPEG icon from a JPEG upload matching the golden bytes" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val originalLogoFileStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        ZIO
          .scoped(for {
            processedLogo <- imageProcessing.processOrganizationLogo(originalLogoFileStream, SupportedMediaTypes.images)
          } yield {
            processedLogo.originalLogoFileName.value shouldBe "original.jpeg"
            processedLogo.normalizedLogoFileName.value shouldBe "normalized.webp"
            processedLogo.whatsAppLogoFileName.value shouldBe "whatsapp.jpeg"

            val originalLogoBytes   = processedLogo.originalLogo.runCollect.zioValue
            val normalizedLogoBytes = processedLogo.normalizedLogo.runCollect.zioValue
            val whatsAppLogoBytes   = processedLogo.whatsAppLogo.runCollect.zioValue

            originalLogoBytes shouldBe originalLogoFileStream.runCollect.zioValue
            normalizedLogoBytes shouldBe createOrGetGolden("normalized-logo-1.webp", normalizedLogoBytes)
            whatsAppLogoBytes shouldBe createOrGetGolden("whatsapp-logo-1.jpeg", whatsAppLogoBytes)
          })
          .zioValue
      }

      "derive the normalized WebP and WhatsApp JPEG icon from a WebP upload matching the golden bytes" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val originalLogoFileStream = ZStream.fromResource("assets/test-logo-2.webp")

        ZIO
          .scoped(for {
            processedLogo <- imageProcessing.processOrganizationLogo(originalLogoFileStream, SupportedMediaTypes.images)
          } yield {
            processedLogo.originalLogoFileName.value shouldBe "original.webp"
            processedLogo.normalizedLogoFileName.value shouldBe "normalized.webp"
            processedLogo.whatsAppLogoFileName.value shouldBe "whatsapp.jpeg"

            val originalLogoBytes   = processedLogo.originalLogo.runCollect.zioValue
            val normalizedLogoBytes = processedLogo.normalizedLogo.runCollect.zioValue
            val whatsAppLogoBytes   = processedLogo.whatsAppLogo.runCollect.zioValue

            originalLogoBytes shouldBe originalLogoFileStream.runCollect.zioValue
            normalizedLogoBytes shouldBe createOrGetGolden("normalized-logo-2.webp", normalizedLogoBytes)
            whatsAppLogoBytes shouldBe createOrGetGolden("whatsapp-logo-2.jpeg", whatsAppLogoBytes)
          })
          .zioValue
      }

      "derive the normalized WebP and WhatsApp JPEG icon from a PNG upload matching the golden bytes" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val originalLogoFileStream = ZStream.fromResource("assets/test-logo-3.png")

        ZIO
          .scoped(for {
            processedLogo <- imageProcessing.processOrganizationLogo(originalLogoFileStream, SupportedMediaTypes.images)
          } yield {
            processedLogo.originalLogoFileName.value shouldBe "original.png"
            processedLogo.normalizedLogoFileName.value shouldBe "normalized.webp"
            processedLogo.whatsAppLogoFileName.value shouldBe "whatsapp.jpeg"

            val originalLogoBytes   = processedLogo.originalLogo.runCollect.zioValue
            val normalizedLogoBytes = processedLogo.normalizedLogo.runCollect.zioValue
            val whatsAppLogoBytes   = processedLogo.whatsAppLogo.runCollect.zioValue

            originalLogoBytes shouldBe originalLogoFileStream.runCollect.zioValue
            normalizedLogoBytes shouldBe createOrGetGolden("normalized-logo-3.webp", normalizedLogoBytes)
            whatsAppLogoBytes shouldBe createOrGetGolden("whatsapp-logo-3.jpeg", whatsAppLogoBytes)
          })
          .zioValue
      }

      "fail to decode when the bytes are not a real image" in {
        val imageProcessing = ZIO
          .service[ImageProcessing]
          .provide(ImageProcessing.live)
          .zioValue

        val result = ZIO
          .scoped(imageProcessing.processOrganizationLogo(ZStream.fromResource("assets/malformed.png"), SupportedMediaTypes.images))
          .zioEither

        result.left.value shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        result.left.value.asInstanceOf[ServiceError.InternalServerError.UnexpectedError].error shouldBe
          "Failed to decode organization logo"
      }
    }
  }
}
