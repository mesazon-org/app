package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.{FileBytesSize, ServiceError, SupportedMediaType}
import org.apache.tika.Tika
import zio.*
import zio.stream.*

import java.io.BufferedOutputStream
import java.nio.file.Files

trait FileScanner {
  def scan(
      fileByteStream: ZStream[Any, Throwable, Byte],
      supportedMediaTypes: List[SupportedMediaType],
      maxFileBytes: Long,
  ): ZIO[Scope, ServiceError, FileScannerScanOutput]
}

object FileScanner {

  private final class FileScannerImpl extends FileScanner {

    private val ExtraBytesToRead = 1L // Read one extra byte to check if the file exceeds the max size
    private val tika             = new Tika()

    override def scan(
        fileByteStream: ZStream[Any, Throwable, Byte],
        supportedMediaTypes: List[SupportedMediaType],
        maxFileBytes: Long,
    ): ZIO[Scope, ServiceError, FileScannerScanOutput] =
      for {
        tempFile <- TempFile.createScoped("file-")
        // Drain the whole incoming stream even once the byte cap is hit instead of stopping early:
        // abandoning the rest of an oversized request body leaves the HTTP connection unread, which
        // can stall the server's request handling for unrelated connections.
        bytesWritten <- ZIO.acquireReleaseWith(
          ZIO
            .attemptBlocking(new BufferedOutputStream(Files.newOutputStream(tempFile)))
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to open temp file", Some(e)))
        )(outputStream => ZIO.attemptBlocking(outputStream.close()).ignoreLogged) { outputStream =>
          fileByteStream
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError("Failed to write file to temp file", Some(e))
            )
            .chunks
            .runFoldZIO(0L) { (written, chunk) =>
              val allowedToWrite =
                math.max(0L, maxFileBytes + ExtraBytesToRead - written).min(Int.MaxValue.toLong).toInt
              val chunkToWrite = chunk.take(allowedToWrite)
              ZIO
                .attemptBlocking(if (chunkToWrite.nonEmpty) outputStream.write(chunkToWrite.toArray))
                .mapError(e =>
                  ServiceError.InternalServerError.UnexpectedError("Failed to write file to temp file", Some(e))
                )
                .as(written + chunk.size)
            }
        }
        _ <-
          ZIO.when(bytesWritten > maxFileBytes)(
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"File size exceeds the maximum allowed size of [$maxFileBytes bytes]"
              )
            )
          )
        mimeTypeDetected <- ZIO
          .attemptBlocking(tika.detect(tempFile))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to detect file type", Some(e)))
        supportedMediaType <- ZIO
          .fromOption(supportedMediaTypes.find(_.mime == mimeTypeDetected))
          .orElseFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Unsupported file type: [$mimeTypeDetected]. Supported file types are: [${supportedMediaTypes.map(_.mime).mkString(", ")}]"
            )
          )
        fileBytesSize <- ZIO
          .fromEither(FileBytesSize.either(bytesWritten))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct FileBytesSize: [$e]"))
      } yield (
        fileByteStreamScanned = FileByteStreamScanned(ZStream.fromPath(tempFile)),
        supportedMediaType = supportedMediaType,
        fileBytesSize = fileBytesSize,
      )
  }

  val live = ZLayer.derive[FileScannerImpl].project[FileScanner](identity)
}
