package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import org.apache.tika.Tika
import zio.*
import zio.stream.*

import java.io.BufferedOutputStream
import java.nio.file.{Files, Path}
import java.util.Locale

trait FileScanner {
  def scan(
      fileByteStream: ZStream[Any, Throwable, Byte],
      supportedMediaTypes: List[SupportedMediaType],
      maxFileBytes: Long,
  ): ZIO[Scope, ServiceError, FileScannerScanOutput]

  def scanV1(
      fileByteStream: ZStream[Any, Throwable, Byte],
      fileNameDeclared: FileNameDeclared,
      supportedMediaTypes: List[SupportedMediaType],
      maxFileBytes: Long,
  ): ZIO[Scope, ServiceError, FileScannerScanV1Output]
}

object FileScanner {

  private final class FileScannerImpl extends FileScanner {

    private val ExtraBytesToRead = 1L // Read one extra byte to check if the file exceeds the max size
    private val tika             = new Tika()

    inline private val SyntheticUploadFileNamePrefix = "upload."
    inline private val FileNameDeclaredField         = "fileNameDeclared"

    private def detectMediaType(tempFile: Path, supportedMediaTypes: List[SupportedMediaType]): Task[String] =
      ZIO.attemptBlocking(tika.detect(tempFile)).flatMap { mimeTypeDetectedMagicOnly =>
        if (supportedMediaTypes.exists(_.mimes.contains(mimeTypeDetectedMagicOnly))) {
          ZIO.succeed(mimeTypeDetectedMagicOnly)
        } else {
          ZIO
            .foldLeft(
              supportedMediaTypes
                .flatMap(mediaType => mediaType.extensions.toSortedSet.iterator.map(mediaType -> _).toList)
            )(
              Option.empty[String]
            ) { (mimeTypeDetectedHintedOpt, candidate) =>
              mimeTypeDetectedHintedOpt match {
                case alreadyMatched @ Some(_) => ZIO.succeed(alreadyMatched)
                case None                     =>
                  ZIO.attemptBlocking {
                    val inputStream = Files.newInputStream(tempFile)
                    try tika.detect(inputStream, s"$SyntheticUploadFileNamePrefix${candidate._2}")
                    finally inputStream.close()
                  }
                    .map(mimeTypeDetectedHinted =>
                      Option.when(candidate._1.mimes.contains(mimeTypeDetectedHinted))(mimeTypeDetectedHinted)
                    )
              }
            }
            .map(_.getOrElse(mimeTypeDetectedMagicOnly))
        }
      }

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
        mimeTypeDetected <- detectMediaType(tempFile, supportedMediaTypes)
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to detect file type", Some(e)))
        supportedMediaType <- ZIO
          .fromOption(supportedMediaTypes.find(_.mimes.contains(mimeTypeDetected)))
          .orElseFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Unsupported file type: [$mimeTypeDetected]. Supported file types are: [${supportedMediaTypes.flatMap(_.mimes.toSortedSet).mkString(", ")}]"
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

    private def validateFileExtension(
        fileNameDeclared: FileNameDeclared,
        supportedMediaTypes: List[SupportedMediaType],
    ): ZIO[Any, ServiceError, SupportedMediaType] =
      for {
        fileName                = fileNameDeclared.value
        fileExtensionStartIndex = fileName.lastIndexOf('.')
        fileExtension <- ZIO
          .fromOption(
            Option
              .when(fileExtensionStartIndex >= 0 && fileExtensionStartIndex < fileName.length - 1)(
                fileName.substring(fileExtensionStartIndex + 1).toLowerCase(Locale.ROOT)
              )
          )
          .orElseFail(
            ServiceError.BadRequestError.ValidationError(
              Seq(
                ServiceError.BadRequestError.InvalidFieldError(
                  FileNameDeclaredField,
                  "File name declaration must contain an extension",
                  fileName,
                )
              )
            )
          )
        supportedMediaType <- ZIO
          .fromOption(supportedMediaTypes.find(_.extensions.contains(fileExtension)))
          .orElseFail(
            ServiceError.BadRequestError.ValidationError(
              Seq(
                ServiceError.BadRequestError.InvalidFieldError(
                  FileNameDeclaredField,
                  s"File name declaration has unsupported extension [$fileExtension]",
                  fileName,
                )
              )
            )
          )
      } yield supportedMediaType

    private def validateFileBytesSize(
        fileByteStream: ZStream[Any, Throwable, Byte],
        fileBytesMax: Long,
    ): ZIO[Scope, ServiceError, (Path, FileBytesSize)] =
      for {
        tempFile         <- TempFile.createScoped("file-")
        fileBytesWritten <- ZIO.acquireReleaseWith(
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
                math.max(0L, fileBytesMax + ExtraBytesToRead - written).min(Int.MaxValue.toLong).toInt
              val chunkToWrite = chunk.take(allowedToWrite)
              ZIO
                .attemptBlocking(if (chunkToWrite.nonEmpty) outputStream.write(chunkToWrite.toArray))
                .mapError(e =>
                  ServiceError.InternalServerError.UnexpectedError("Failed to write file to temp file", Some(e))
                )
                .as(written + chunk.size)
            }
        }
        _ <- ZIO.when(fileBytesWritten > fileBytesMax)(
          ZIO.fail(
            ServiceError.InternalServerError.UnexpectedError(
              s"File size exceeds the maximum allowed size of [$fileBytesMax bytes]"
            )
          )
        )
        fileBytesSize <- ZIO
          .fromEither(FileBytesSize.either(fileBytesWritten))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct FileBytesSize: [$e]"))
      } yield (tempFile, fileBytesSize)

    private def validateMediaType(
        fileTempPath: Path,
        fileNameDeclared: FileNameDeclared,
        supportedMediaType: SupportedMediaType,
    ): ZIO[Any, ServiceError, Unit] =
      for {
        mimeTypeDetected <- ZIO
          .acquireReleaseWith(ZIO.attemptBlocking(Files.newInputStream(fileTempPath)))(inputStream =>
            ZIO.attemptBlocking(inputStream.close()).ignoreLogged
          )(inputStream => ZIO.attemptBlocking(tika.detect(inputStream, fileNameDeclared.value)))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to detect file type", Some(e)))
        _ <- ZIO.unlessDiscard(supportedMediaType.mimes.contains(mimeTypeDetected))(
          ZIO.fail(
            ServiceError.BadRequestError.ValidationError(
              Seq(
                ServiceError.BadRequestError.InvalidFieldError(
                  FileNameDeclaredField,
                  s"File name declaration does not match detected media type [$mimeTypeDetected]",
                  fileNameDeclared.value,
                )
              )
            )
          )
        )
      } yield ()

    override def scanV1(
        fileByteStream: ZStream[Any, Throwable, Byte],
        fileNameDeclared: FileNameDeclared,
        supportedMediaTypes: List[SupportedMediaType],
        fileBytesMax: Long,
    ): ZIO[Scope, ServiceError, FileScannerScanV1Output] =
      for {
        supportedMediaType            <- validateFileExtension(fileNameDeclared, supportedMediaTypes)
        (fileTempPath, fileBytesSize) <- validateFileBytesSize(fileByteStream, fileBytesMax)
        _                             <- validateMediaType(fileTempPath, fileNameDeclared, supportedMediaType)
      } yield (
        fileScannedPath = FileScannedPath(fileTempPath),
        supportedMediaType = supportedMediaType,
        fileBytesSize = fileBytesSize,
      )
  }

  val live = ZLayer.derive[FileScannerImpl].project[FileScanner](identity)
}
