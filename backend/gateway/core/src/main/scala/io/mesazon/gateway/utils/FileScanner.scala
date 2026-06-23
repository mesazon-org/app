package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.FileScanner.SupportedTypes
import org.apache.tika.Tika
import zio.*
import zio.stream.*

trait FileScanner {
  def scan(
      fileBytes: ZStream[Any, Throwable, Byte],
      supportedTypes: List[SupportedTypes],
      maxBytes: Long,
  ): ZIO[Scope, ServiceError, ZStream[Any, Throwable, Byte]]
}

object FileScanner {

  enum SupportedTypes(val ext: String, val mime: String) {
    case PNG  extends SupportedTypes("png", "image/png")
    case JPEG extends SupportedTypes("jpg", "image/jpeg")
    case WEBP extends SupportedTypes("webp", "image/webp")
  }

  object SupportedTypes {
    val images: List[SupportedTypes] = List(PNG, JPEG, WEBP)
  }

  private final class FileScannerImpl extends FileScanner {

    private val tika = new Tika()

    override def scan(
        fileBytes: ZStream[Any, Throwable, Byte],
        supportedTypes: List[SupportedTypes],
        maxBytes: Long,
    ): ZIO[Scope, ServiceError, ZStream[Any, Throwable, Byte]] =
      for {
        tempFile     <- TempFile.createScoped("file-")
        bytesWritten <- fileBytes
          .take(maxBytes + 1)
          .run(ZSink.fromPath(tempFile))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to write file to temp file", Some(e)))
        _ <-
          ZIO.when(bytesWritten > maxBytes)(
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"File size exceeds the maximum allowed size of [$maxBytes bytes]"
              )
            )
          )
        mimeTypeDetected <- ZIO
          .attempt(tika.detect(tempFile))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to detect file type", Some(e)))
        _ <-
          ZIO.unless(supportedTypes.exists(_.mime == mimeTypeDetected))(
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unsupported file type: [$mimeTypeDetected]. Supported types are: [${supportedTypes.map(_.mime).mkString(", ")}]"
              )
            )
          )
      } yield ZStream.fromPath(tempFile)
  }

  val live = ZLayer.derive[FileScannerImpl].project[FileScanner](identity)
}
