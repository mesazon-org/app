package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import io.mesazon.gateway.utils.FileScanner.SupportedTypes
import org.apache.tika.Tika
import zio.*
import zio.stream.{ZSink, ZStream}

import java.nio.file.{Files, Path}

trait FileScanner {
  def scan(
      fileBytes: ZStream[Any, Throwable, Byte],
      supportedTypes: List[SupportedTypes],
      maxBytes: Long,
  ): IO[ServiceError, Unit]
}

object FileScanner {

  enum SupportedTypes(val ext: String, val mime: String) {
    case PNG  extends SupportedTypes("png", "image/png")
    case JPEG extends SupportedTypes("jpg", "image/jpeg")
    case SVG  extends SupportedTypes("svg", "image/svg+xml")
    case WEBP extends SupportedTypes("webp", "image/webp")
  }

  object SupportedTypes {
    val images: List[SupportedTypes] = List(PNG, JPEG, SVG, WEBP)
  }

  private final class FileScannerImpl() extends FileScanner {

    private val tika = new Tika()

    private def createTempFileScoped: ZIO[Scope, ServiceError.InternalServerError.UnexpectedError, Path] =
      ZIO
        .acquireRelease(
          ZIO.attempt(Files.createTempFile("file-", ".tmp"))
        )(path => ZIO.attempt(Files.deleteIfExists(path)).ignoreLogged)
        .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create temp file", Some(e)))

    override def scan(
        fileBytes: ZStream[Any, Throwable, Byte],
        supportedTypes: List[SupportedTypes],
        maxBytes: Long,
    ): IO[ServiceError, Unit] =
      ZIO.scoped(
        for {
          tempFilePath  <- createTempFileScoped
          fileSizeBytes <- fileBytes
            .run(ZSink.fromPath(tempFilePath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to write organization logo to temp file", Some(e))
            )
          mimeTypeDetected <- ZIO
            .attempt(tika.detect(tempFilePath))
            .mapError(error =>
              ServiceError.InternalServerError.UnexpectedError("Failed to detect file type", Some(error))
            )
          _ <-
            if (supportedTypes.exists(_.mime == mimeTypeDetected)) ZIO.unit
            else
              ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError(
                  s"Unsupported file type: [$mimeTypeDetected]. Supported types are: [${supportedTypes.map(_.mime).mkString(", ")}]"
                )
              )
          _ <-
            if (fileSizeBytes <= maxBytes) ZIO.unit
            else
              ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError(
                  s"File size [$fileSizeBytes bytes] exceeds the maximum allowed size of [$maxBytes bytes]"
                )
              )
        } yield ()
      )
  }

  val live = ZLayer.derive[FileScannerImpl].project[FileScanner](identity)
}
