package io.rikkos.gateway.it.client

import cats.syntax.all.*
import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import fs2.io.net.Network
import io.rikkos.gateway.it.client.GatewayApiClient.*
import io.rikkos.gateway.it.domain.OnboardUserDetailsRequest
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Authorization
import zio.*
import zio.interop.catz.*

final case class GatewayApiClient(config: GatewayApiClientConfig, client: Client[Task]) {
  import config.*

  def liveness: Task[Status] = client.get(healthUri / "liveness")(_.status.pure[Task])

  def readiness: Task[Status] = client.get(healthUri / "readiness")(_.status.pure[Task])

  def userOnboard(
      onboardUserDetailsRequest: OnboardUserDetailsRequest
  )(using EntityEncoder[Task, OnboardUserDetailsRequest]): Task[Status] = {
    val request = Request[Task](Method.POST, serviceUri / "users" / "onboard")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(onboardUserDetailsRequest)

    ZIO
      .scoped(client.run(request).toScopedZIO)
      .map(_.status)
  }
}

object GatewayApiClient {
  lazy val ServiceName = "gateway"
  lazy val ServicePort = 8080
  lazy val HealthPort  = 8081
  lazy val ExposedServices = Set(
    ExposedService(ServiceName, ServicePort),
    ExposedService(ServiceName, HealthPort),
  )

  final case class GatewayApiClientConfig(serviceUri: Uri, healthUri: Uri, token: String)

  def createClient(container: DockerComposeContainer)(using Network[Task]): ZIO[Scope, Throwable, GatewayApiClient] =
    for {
      token       = "valid-token" // TODO: replace with actual token later when authorization service is implemented
      host        = container.getServiceHost(ServiceName, ServicePort)
      servicePort = container.getServicePort(ServiceName, ServicePort)
      healthPort  = container.getServicePort(ServiceName, HealthPort)
      config = GatewayApiClientConfig(
        Uri.unsafeFromString(s"http://$host:$servicePort"),
        Uri.unsafeFromString(s"http://$host:$healthPort"),
        token,
      )
      client <- EmberClientBuilder
        .default[Task]
        .build
        .toScopedZIO
    } yield GatewayApiClient(config, client)
}
