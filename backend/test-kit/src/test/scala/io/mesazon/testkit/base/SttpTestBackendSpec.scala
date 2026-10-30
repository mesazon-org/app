package io.mesazon.testkit.base

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import sttp.capabilities.zio.ZioStreams
import sttp.client4.*
import sttp.model.{StatusCode, Uri}
import zio.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class SttpTestBackendSpec extends ZWordSpecBase {

  private inline val localHttpServerResponseBody   = "sttp-test-backend-response"
  private inline val localHttpServerPath           = "sttp-test-backend"
  private inline val localHttpServerAnyPort        = 0
  private inline val localHttpServerNoBacklog      = 0
  private inline val localHttpServerStopNoDelay    = 0
  private val localHttpServerResponseBodyBytes     = localHttpServerResponseBody.getBytes(StandardCharsets.UTF_8)

  case class Context(localHttpServerUri: Uri)

  def withContext[A](f: Context => A): A = {
    val localHttpServer =
      HttpServer.create(new InetSocketAddress(localHttpServerAnyPort), localHttpServerNoBacklog)

    localHttpServer.createContext(
      s"/$localHttpServerPath",
      (httpExchange: HttpExchange) => {
        httpExchange.sendResponseHeaders(StatusCode.Ok.code, localHttpServerResponseBodyBytes.length.toLong)
        httpExchange.getResponseBody.write(localHttpServerResponseBodyBytes)
        httpExchange.close()
      },
    )
    localHttpServer.start()

    val localHttpServerUri =
      Uri.unsafeParse(s"http://localhost:${localHttpServer.getAddress.getPort}/$localHttpServerPath")

    try f(Context(localHttpServerUri))
    finally localHttpServer.stop(localHttpServerStopNoDelay)
  }

  "SttpTestBackend" when {
    "live" should {
      "answer a request through a backend taken from an already completed providing effect" in withContext {
        context =>
          import context.*

          val sttpTestBackend = ZIO
            .service[WebSocketStreamBackend[Task, ZioStreams]]
            .provide(SttpTestBackend.live)
            .zioValue

          val localHttpServerResponse = basicRequest
            .get(localHttpServerUri)
            .response(asStringOrFail)
            .send(sttpTestBackend)
            .zioValue

          localHttpServerResponse.code shouldBe StatusCode.Ok
          localHttpServerResponse.body shouldBe localHttpServerResponseBody
      }

      "answer requests through the same backend in every providing effect" in withContext { context =>
        import context.*

        val sttpTestBackendFirst = ZIO
          .service[WebSocketStreamBackend[Task, ZioStreams]]
          .provide(SttpTestBackend.live)
          .zioValue

        val sttpTestBackendSecond = ZIO
          .service[WebSocketStreamBackend[Task, ZioStreams]]
          .provide(SttpTestBackend.live)
          .zioValue

        sttpTestBackendSecond shouldBe theSameInstanceAs(sttpTestBackendFirst)

        val localHttpServerResponseFirst = basicRequest
          .get(localHttpServerUri)
          .response(asStringOrFail)
          .send(sttpTestBackendFirst)
          .zioValue

        val localHttpServerResponseSecond = basicRequest
          .get(localHttpServerUri)
          .response(asStringOrFail)
          .send(sttpTestBackendSecond)
          .zioValue

        localHttpServerResponseFirst.code shouldBe StatusCode.Ok
        localHttpServerResponseFirst.body shouldBe localHttpServerResponseBody
        localHttpServerResponseSecond.code shouldBe StatusCode.Ok
        localHttpServerResponseSecond.body shouldBe localHttpServerResponseBody
      }

      "answer a request through a backend that was closed" in withContext { context =>
        import context.*

        val sttpTestBackend = ZIO
          .service[WebSocketStreamBackend[Task, ZioStreams]]
          .provide(SttpTestBackend.live)
          .zioValue

        sttpTestBackend.close().zioValue

        val localHttpServerResponse = basicRequest
          .get(localHttpServerUri)
          .response(asStringOrFail)
          .send(sttpTestBackend)
          .zioValue

        localHttpServerResponse.code shouldBe StatusCode.Ok
        localHttpServerResponse.body shouldBe localHttpServerResponseBody
      }
    }
  }
}
