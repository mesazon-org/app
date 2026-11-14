package io.mesazon.testkit.base

import sttp.capabilities.zio.ZioStreams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*

import java.net.http.HttpClient

object SttpTestBackend {

  private lazy val httpClient: HttpClient = HttpClient.newBuilder().build()

  private lazy val backend: WebSocketStreamBackend[Task, ZioStreams] = HttpClientZioBackend.usingClient(httpClient)

  val live: ZLayer[Any, Nothing, WebSocketStreamBackend[Task, ZioStreams]] = ZLayer.succeed(backend)

  // Runs on ZIO's executor; close() stops the client's selector thread, which sttp's own close() never does.
  val liveScoped: ZLayer[Any, Throwable, WebSocketStreamBackend[Task, ZioStreams]] = ZLayer.scoped(
    ZIO
      .acquireRelease(
        ZIO.executor.flatMap(zioExecutor => ZIO.attempt(HttpClient.newBuilder().executor(zioExecutor.asJava).build()))
      )(httpClient => ZIO.attemptBlocking(httpClient.close()).orDie)
      .map(httpClient => HttpClientZioBackend.usingClient(httpClient))
  )
}
