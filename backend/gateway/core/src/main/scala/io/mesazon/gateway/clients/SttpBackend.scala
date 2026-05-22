package io.mesazon.gateway.clients

import io.mesazon.gateway.config.HttpClientConfig
import sttp.capabilities.zio.ZioStreams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.logging.slf4j.Slf4jLoggingBackend
import zio.*

import java.net.http.HttpClient

object SttpBackend {

  val live: ZLayer[HttpClientConfig, Throwable, WebSocketStreamBackend[Task, ZioStreams]] = ZLayer.scoped(
    (for {
      config <- ZIO.service[HttpClientConfig]
      httpClient = HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(config.connectionTimeout.asJava)
        .build()
      backend <- ZIO.attempt(HttpClientZioBackend.usingClient(httpClient))
      loggingBackend = Slf4jLoggingBackend(backend)
    } yield loggingBackend).tap(client => ZIO.addFinalizer(client.close().ignore))
  )
}
