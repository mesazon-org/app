package io.mesazon.waha

import io.mesazon.domain.waha.{WahaError, WahaErrorCode}
import sttp.client4.{Backend, Request, Response, ResponseException}
import sttp.model.Header
import zio.*

inline def getApiKeyHeader(apiKey: String): Header = Header.unsafeApply("X-Api-Key", apiKey)

extension [A](request: Request[Either[ResponseException[String], A]]) {
  def standardSendRequest(errorCode: WahaErrorCode)(using backend: Backend[Task]): IO[WahaError, Response[A]] =
    request
      .send(backend)
      .mapError(error => WahaError(errorCode, None, Some(error)))
      .flatMap { response =>
        response.body.left
          .map(error => WahaError(errorCode, Some(error.getMessage), Some(error)))
          .fold(ZIO.fail, body => ZIO.succeed(response.copy(body = body)))
      }
      .logError("Waha request failed")
}

extension [A](request: Request[Either[String, A]]) {
  def standardSendRequestString(errorCode: WahaErrorCode)(using backend: Backend[Task]): IO[WahaError, Response[A]] =
    request
      .send(backend)
      .mapError(error => WahaError(errorCode, None, Some(error)))
      .flatMap { response =>
        response.body.left
          .map(error => WahaError(errorCode, Some(error), None))
          .fold(ZIO.fail, body => ZIO.succeed(response.copy(body = body)))
      }
      .logError("Waha request failed")
}

extension [A](request: Request[Unit]) {
  def standardSendRequestUnit(errorCode: WahaErrorCode)(using backend: Backend[Task]): IO[WahaError, Response[Unit]] =
    request
      .send(backend)
      .mapError(error => WahaError(errorCode, None, Some(error)))
      .logError("Waha request failed")
}
