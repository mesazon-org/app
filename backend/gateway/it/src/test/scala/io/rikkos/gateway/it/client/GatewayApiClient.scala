package io.rikkos.gateway.it.client

import cats.syntax.all.*
import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
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

  final case class GatewayApiClientConfig(serviceUri: Uri, healthUri: Uri, token: String) {

    /** @param containers
      *   Option[DockerComposeContainer] * If provided resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   GatewayApiClientConfig
      */
    def adjust(
        containers: Option[DockerComposeContainer],
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
    ): GatewayApiClientConfig = containers match {
      case Some(containers) =>
        GatewayApiClientConfig.from(
          containers,
          serviceName,
          servicePort,
        )
      case None => this
    }
  }

  object GatewayApiClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   GatewayApiClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
        token: String = "valid-token",
    ): GatewayApiClientConfig = {
      val host        = containers.getServiceHost(ServiceName, ServicePort)
      val servicePort = containers.getServicePort(ServiceName, ServicePort)
      val healthPort  = containers.getServicePort(ServiceName, HealthPort)

      GatewayApiClientConfig(
        Uri.unsafeFromString(s"http://$host:$servicePort"),
        Uri.unsafeFromString(s"http://$host:$healthPort"),
        token,
      )
    }
  }

  val live =
    ZLayer.scoped(EmberClientBuilder.default[Task].build.toScopedZIO) >>> ZLayer.fromFunction(GatewayApiClient.apply)
}
