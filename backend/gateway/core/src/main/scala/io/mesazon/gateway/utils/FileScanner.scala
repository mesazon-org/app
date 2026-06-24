package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.{ServiceError, SupportedMediaTypes}
import org.apache.tika.Tika
import zio.*
import zio.stream.*

trait FileScanner {
  def scan(
      fileBytes: ZStream[Any, Throwable, Byte],
      supportedMediaTypes: List[SupportedMediaTypes],
      maxFileBytes: Long,
  ): ZIO[Scope, ServiceError, FileBytesScanned]
}

object FileScanner {

  private final class FileScannerImpl extends FileScanner {

    private val tika = new Tika()

    override def scan(
        fileBytes: ZStream[Any, Throwable, Byte],
        supportedMediaTypes: List[SupportedMediaTypes],
        maxFileBytes: Long,
    ): ZIO[Scope, ServiceError, FileBytesScanned] =
      for {
        tempFile     <- TempFile.createScoped("file-")
        bytesWritten <- fileBytes
          .take(maxFileBytes + 1)
          .run(ZSink.fromPath(tempFile))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to write file to temp file", Some(e)))
        _ <-
          ZIO.when(bytesWritten > maxFileBytes)(
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"File size exceeds the maximum allowed size of [$maxFileBytes bytes]"
              )
            )
          )
        mimeTypeDetected <- ZIO
          .attempt(tika.detect(tempFile))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to detect file type", Some(e)))
        _ <-
          ZIO.unless(supportedMediaTypes.exists(_.mime == mimeTypeDetected))(
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unsupported file type: [$mimeTypeDetected]. Supported file types are: [${supportedMediaTypes.map(_.mime).mkString(", ")}]"
              )
            )
          )
      } yield FileBytesScanned(ZStream.fromPath(tempFile))
  }

  val live = ZLayer.derive[FileScannerImpl].project[FileScanner](identity)
}
