package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.ServiceError
import zio.*

import java.nio.file.{Files, Path}

object TempFile {

  def createScoped(prefix: String)(using Trace): ZIO[Scope, ServiceError, Path] =
    ZIO
      .acquireRelease(
        ZIO.attempt(Files.createTempFile(prefix, ".tmp"))
      )(path => ZIO.attempt(Files.deleteIfExists(path)).ignoreLogged)
      .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create temp file", Some(e)))
}