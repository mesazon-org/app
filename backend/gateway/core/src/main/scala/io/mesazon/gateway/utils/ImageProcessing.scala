package io.mesazon.gateway.utils

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.format.{Format, FormatDetector}
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.webp.WebpWriter
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.utils.ImageProcessing.OrganizationLogosProcessed
import zio.*
import zio.stream.*

import java.nio.file.Files
import scala.jdk.OptionConverters.*

trait ImageProcessing {
  def processOrganizationLogo(
      originalLogoBytes: FileBytesScanned,
      supportedMediaTypes: List[SupportedMediaTypes],
  ): ZIO[Scope, ServiceError, OrganizationLogosProcessed]
}

object ImageProcessing {

  type OrganizationLogosProcessed = (
      originalLogoProcessed: OriginalLogoProcessed,
      normalizedLogoProcessed: NormalizedLogoProcessed,
      whatsAppLogoProcessed: WhatsAppLogoProcessed,
  )

  private val MaxDimensionPixels  = 512
  private val WhatsAppIconPixels  = 640
  private val WhatsAppJpegQuality = 80

  private final class ImageProcessingImpl() extends ImageProcessing {

    private def isSupportedFormat(
        format: Format,
        supportedMediaTypes: List[SupportedMediaTypes],
    ): Boolean =
      supportedMediaTypes.exists(mt => format.toString.equalsIgnoreCase(mt.toString))

    override def processOrganizationLogo(
        originalLogoBytes: FileBytesScanned,
        supportedMediaTypes: List[SupportedMediaTypes],
    ): ZIO[Scope, ServiceError, OrganizationLogosProcessed] =
      for {
        originalLogoFile <- TempFile.createScoped("original-logo-")
        _                <- originalLogoBytes.value
          .run(ZSink.fromPath(originalLogoFile))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to write image to temp file", Some(e))
          )
        originalFormat <- ZIO
          .fromAutoCloseable(ZIO.attemptBlocking(Files.newInputStream(originalLogoFile)))
          .flatMap(inputStream => ZIO.attemptBlocking(FormatDetector.detect(inputStream).toScala))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to detect organization logo format", Some(e))
          )
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError("Unsupported organization logo format")
          )
        _ <-
          if (isSupportedFormat(originalFormat, supportedMediaTypes)) ZIO.unit
          else
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unsupported organization logo format: [${originalFormat.toString}]. Supported formats are: [${supportedMediaTypes.map(_.toString).mkString(", ")}]"
              )
            )
        immutableImage <- ZIO
          .attemptBlocking(ImmutableImage.loader().fromPath(originalLogoFile))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to decode organization logo", Some(e))
          )
        normalizedLogoFile <- TempFile.createScoped("normalized-logo-")
        _                  <- ZIO
          .attemptBlocking(
            immutableImage
              .bound(MaxDimensionPixels, MaxDimensionPixels)
              .forWriter(WebpWriter.DEFAULT.withLossless())
              .write(normalizedLogoFile)
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to write organization normalized logo", Some(e))
          )
        whatsAppLogoFile <- TempFile.createScoped("normalized-logo-")
        _                <- ZIO
          .attemptBlocking(
            immutableImage
              .cover(WhatsAppIconPixels, WhatsAppIconPixels)
              .forWriter(JpegWriter.compression(WhatsAppJpegQuality))
              .write(whatsAppLogoFile)
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to write organization whats app logo", Some(e))
          )
      } yield (
        OriginalLogoProcessed(ZStream.fromPath(originalLogoFile)),
        NormalizedLogoProcessed(ZStream.fromPath(normalizedLogoFile)),
        WhatsAppLogoProcessed(ZStream.fromPath(whatsAppLogoFile)),
      )
  }

  val live = ZLayer.derive[ImageProcessingImpl].project[ImageProcessing](identity)
}
