package io.mesazon.waha.it.client

import cats.syntax.all.*
import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.mesazon.waha.it.client.WiremockClient.*
import sttp.client4.*
import sttp.client4.jsoniter.*
import sttp.model.Uri
import zio.*
import zio.interop.catz.*

case class WiremockClient(config: WiremockClientConfig, backend: Backend[Task]) {

  def healthCheck: UIO[Response[HealthCheckStatus]] =
    basicRequest
      .get(config.baseUri.withPath("__admin", "health"))
      .response(asJsonOrFail[HealthCheckStatus])
      .send(backend)
      .orDie

  def reset: UIO[Response[String]] =
    basicRequest
      .delete(config.baseUri.withPath("__admin", "requests"))
      .response(asStringOrFail)
      .send(backend)
      .orDie

  def requestsDetails: UIO[Seq[RequestDetails]] = for {
    requestLogResponse <- basicRequest
      .get(config.baseUri.withPath("__admin", "requests"))
      .response(asJsonOrFail[RequestLogResponse])
      .send(backend)
      .orDie
    response <- requestLogResponse.body.requests.traverse { requestLog =>
      val requestMapping = RequestMapping(
        method = requestLog.request.method,
        url = requestLog.request.url,
      )
      basicRequest
        .post(config.baseUri.withPath("__admin", "requests", "count"))
        .body(asJson(requestMapping))
        .response(asJsonOrFail[RequestCount])
        .send(backend)
        .orDie
        .map(requestCountResponse =>
          RequestDetails(
            mapping = requestMapping,
            absoluteUrl = requestLog.request.absoluteUrl,
            lastCallDate = requestLog.request.loggedDate,
            count = requestCountResponse.body.count,
          )
        )
    }
  } yield response

}

object WiremockClient {
  lazy val ServiceName     = "wiremock"
  lazy val ServicePort     = 8080
  lazy val ExposedServices = Set(
    ExposedService(ServiceName, ServicePort)
  )

  given JsonValueCodec[HealthCheckStatus]  = JsonCodecMaker.make
  given JsonValueCodec[RequestMapping]     = JsonCodecMaker.make
  given JsonValueCodec[RequestLogResponse] = JsonCodecMaker.make
  given JsonValueCodec[RequestCount]       = JsonCodecMaker.make

  case class HealthCheckStatus(status: String)

  case class RequestMapping(
      method: String,
      url: String,
  )

  case class RequestDetails(
      mapping: RequestMapping,
      absoluteUrl: String,
      lastCallDate: Long,
      count: Int,
  )

  case class RequestCount(
      count: Int
  )

  case class RequestLogResponse(
      requests: Seq[RequestLog]
  )

  case class RequestLog(
      id: String,
      request: LoggedRequest,
  )

  case class LoggedRequest(
      method: String,
      url: String,
      loggedDate: Long,
      absoluteUrl: String,
  )
  /*
  {
  "requests": [
    {
      "id": "8a32a3b0-2139-4d32-8e10-123456789abc",
      "request": {
        "method": "POST",
        "url": "/your/endpoint",
        "headers": {
          "Content-Type": "application/json",
          "User-Agent": "PostmanRuntime/7.26.8"
        },
        "body": "{\"username\": \"admin\"}",
        "queryParams": {},
        "loggedInDate": 1625097600000,
        "absoluteUrl": "http://localhost:8080/your/endpoint"
      },
      "responseDefinition": {
        "status": 200,
        "body": "OK"
      },
      "wasMatched": true
    }
  ],
  "meta": {
    "total": 1
  }
}
   */

  case class WiremockClientConfig(scheme: String, host: String, port: Int) {
    val baseUri: Uri = Uri.unsafeApply(scheme, host, port)

    def adjust(
        containers: Option[DockerComposeContainer],
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
    ): WiremockClientConfig = containers match {
      case Some(containers) =>
        WiremockClientConfig.from(
          containers,
          serviceName,
          servicePort,
        )
      case None => this
    }
  }

  object WiremockClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   WiremockClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
    ): WiremockClientConfig = {
      val host = containers.getServiceHost(serviceName, servicePort)
      val port = containers.getServicePort(serviceName, servicePort)

      WiremockClientConfig("http", host, port)
    }
  }

  val live = ZLayer.derive[WiremockClient]
}
