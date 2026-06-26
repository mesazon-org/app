package io.mesazon.gateway.utils

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.format.{Format, FormatDetector}
import com.sksamuel.scrimage.webp.WebpWriter
import io.mesazon.domain.gateway.*
import zio.*
import zio.stream.*

import java.nio.file.Files
import scala.jdk.OptionConverters.*

trait ImageProcessing {
  def normalize(
      imageByteStream: FileByteStreamScanned,
      supportedMediaTypes: List[SupportedMediaTypes],
  ): ZIO[Scope, ServiceError, NormalizeResult]
}

object ImageProcessing {

  private val MaxDimensionPixels = 640

  private final class ImageProcessingImpl() extends ImageProcessing {

    private def isSupportedFormat(
        imageFormat: Format,
        supportedMediaTypes: List[SupportedMediaTypes],
    ): Boolean =
      supportedMediaTypes.exists(mt => imageFormat.toString.equalsIgnoreCase(mt.toString))

    override def normalize(
        imageByteStream: FileByteStreamScanned,
        supportedMediaTypes: List[SupportedMediaTypes],
    ): ZIO[Scope, ServiceError, NormalizeResult] =
      for {
        imagePath <- TempFile.createScoped("image-")
        _         <- imageByteStream.value
          .run(ZSink.fromPath(imagePath))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to write image to temp file", Some(e))
          )
        imageFormat <- ZIO
          .fromAutoCloseable(ZIO.attemptBlocking(Files.newInputStream(imagePath)))
          .flatMap(inputStream => ZIO.attemptBlocking(FormatDetector.detect(inputStream).toScala))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to detect organization logo format", Some(e))
          )
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError("Unsupported organization logo format")
          )
        _ <-
          if (isSupportedFormat(imageFormat, supportedMediaTypes)) ZIO.unit
          else
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unsupported organization logo format: [${imageFormat.toString}]. Supported formats are: [${supportedMediaTypes.map(_.toString).mkString(", ")}]"
              )
            )
        immutableImage <- ZIO
          .attemptBlocking(ImmutableImage.loader().fromPath(imagePath))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to decode organization logo", Some(e))
          )
        imageNormalized <- TempFile.createScoped("image-normalized-")
        _               <- ZIO
          .attemptBlocking(
            immutableImage
              .bound(MaxDimensionPixels, MaxDimensionPixels)
              .forWriter(WebpWriter.DEFAULT.withLossless())
              .write(imageNormalized)
          )
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to write normalized image", Some(e)))
      } yield (
        ImageOriginalByteStream(ZStream.fromPath(imagePath)),
        ImageNormalizedByteStream(ZStream.fromPath(imageNormalized)),
      )
  }

  val live = ZLayer.derive[ImageProcessingImpl].project[ImageProcessing](identity)
}
