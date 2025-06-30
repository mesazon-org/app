package io.rikkos.gateway.it.client

import cats.syntax.all.*
import com.dimafeng.testcontainers.*
import fs2.io.net.Network
import io.rikkos.gateway.it.client.GatewayApiClient.*
import io.rikkos.gateway.it.domain.{OnboardUserDetailsRequest, UpdateUserDetailsRequest}
import io.rikkos.gateway.smithy
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
      onboardUserDetailsRequest: smithy.OnboardUserDetailsRequest
  )(using EntityEncoder[Task, smithy.OnboardUserDetailsRequest]): Task[Status] = {
    val request = Request[Task](Method.POST, serviceUri / "users" / "onboard")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(onboardUserDetailsRequest)

    ZIO
      .scoped(client.run(request).toScopedZIO)
      .map(_.status)
  }

  def userUpdate(
      updateUserDetailsRequest: UpdateUserDetailsRequest
  )(using EntityEncoder[Task, UpdateUserDetailsRequest]): Task[Status] = {
    val request = Request[Task](Method.POST, serviceUri / "users" / "update")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(updateUserDetailsRequest)

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

  given Network[Task] = Network.forAsync[Task]

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
      *   Int container service port
      * @param healthPort
      *   Int container health port
      * @return
      *   GatewayApiClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
        healthPort: Int = HealthPort,
        token: String = "valid-token",
    ): GatewayApiClientConfig = {
      val adjustedHost        = containers.getServiceHost(serviceName, servicePort)
      val adjustedServicePort = containers.getServicePort(serviceName, servicePort)
      val adjustedHealthPort  = containers.getServicePort(serviceName, healthPort)

      GatewayApiClientConfig(
        Uri.unsafeFromString(s"http://$adjustedHost:$adjustedServicePort"),
        Uri.unsafeFromString(s"http://$adjustedHost:$adjustedHealthPort"),
        token,
      )
    }
  }

  val live =
    ZLayer.scoped(EmberClientBuilder.default[Task].build.toScopedZIO) >>> ZLayer.fromFunction(GatewayApiClient.apply)
}
