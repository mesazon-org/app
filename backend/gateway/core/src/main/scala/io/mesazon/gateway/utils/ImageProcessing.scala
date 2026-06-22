package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import zio.*
import zio.stream.ZStream

import java.nio.file.*

trait ImageProcessing {
  def normalizeLogo(organizationLogoFileBytes: ZStream[Any, Throwable, Byte]): IO[ServiceError, Path]
}

object ImageProcessing {

  final case class NormalizedLogoResult(
      originalLogo: ZStream[Any, Throwable, Byte],
      normalizedLogo: ZStream[Any, Throwable, Byte],
                                       )

  private final class ImageProcessingImpl() extends ImageProcessing {

    private def createTempFileScoped: ZIO[Scope, ServiceError.InternalServerError.UnexpectedError, Path] =
      ZIO
        .acquireRelease(
          ZIO.attempt(Files.createTempFile("image-", ".tmp"))
        )(path => ZIO.attempt(Files.deleteIfExists(path)).ignoreLogged)
        .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create temp file", Some(e)))

    override def normalizeLogo(organizationLogoFileBytes: ZStream[Any, Throwable, Byte]): IO[ServiceError, Path] = ???

  }

  val live = ZLayer.derive[ImageProcessingImpl].project[ImageProcessing](identity)
}
