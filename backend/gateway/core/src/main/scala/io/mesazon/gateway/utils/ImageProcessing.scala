package io.mesazon.gateway.utils

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.webp.WebpWriter
import io.mesazon.domain.gateway.ServiceError
import zio.*
import zio.stream.*

trait ImageProcessing {

  def processLogo(
      organizationLogoFileBytes: ZStream[Any, Throwable, Byte]
  ): ZIO[Scope, ServiceError, ImageProcessing.ProcessedLogo]
}

object ImageProcessing {

  case class ProcessedLogo(
      originalLogo: ZStream[Any, Throwable, Byte],
      normalizedLogo: ZStream[Any, Throwable, Byte],
      whatsAppLogo: ZStream[Any, Throwable, Byte],
  )

  private val MaxDimensionPixels  = 512
  private val WhatsAppIconPixels  = 640
  private val WhatsAppJpegQuality = 80

  private final class ImageProcessingImpl extends ImageProcessing {

    private def renditionStream(render: => Array[Byte]): ZStream[Any, Throwable, Byte] =
      ZStream.unwrap(ZIO.attemptBlocking(ZStream.fromChunk(Chunk.fromArray(render))))

    override def processLogo(
        organizationLogoFileBytes: ZStream[Any, Throwable, Byte]
    ): ZIO[Scope, ServiceError, ProcessedLogo] =
      for {
        tempFile <- TempFile.createScoped("logo-")
        _        <- organizationLogoFileBytes
          .run(ZSink.fromPath(tempFile))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to write image to temp file", Some(e))
          )
        image <- ZIO
          .attemptBlocking(ImmutableImage.loader().fromPath(tempFile))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to decode organization logo", Some(e))
          )
      } yield ProcessedLogo(
        originalLogo = ZStream.fromPath(tempFile),
        normalizedLogo = renditionStream(
          image.bound(MaxDimensionPixels, MaxDimensionPixels).bytes(WebpWriter.DEFAULT.withLossless())
        ),
        whatsAppLogo = renditionStream(
          image.cover(WhatsAppIconPixels, WhatsAppIconPixels).bytes(JpegWriter.compression(WhatsAppJpegQuality))
        ),
      )
  }

  val live = ZLayer.derive[ImageProcessingImpl].project[ImageProcessing](identity)
}
