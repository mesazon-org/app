package io.mesazon.gateway.it.client

import cats.syntax.all.*
import com.dimafeng.testcontainers.*
import fs2.io.net.Network
import io.circe.Encoder
import io.mesazon.gateway.smithy
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Authorization
import zio.*
import zio.interop.catz.*

import GatewayApiClient.*

case class GatewayApiClient(config: GatewayApiClientConfig, client: Client[Task]) {
  import config.*

  def liveness: Task[Status] = client.get(healthUri / "liveness")(_.status.pure[Task])

  def readiness: Task[Status] = client.get(healthUri / "readiness")(_.status.pure[Task])

  def onboardUser(
      onboardUserDetailsRequest: smithy.OnboardUserDetailsRequest
  )(using Encoder[smithy.OnboardUserDetailsRequest]): Task[Status] = {
    val request = Request[Task](Method.POST, externalUri / "users" / "onboard")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(onboardUserDetailsRequest)

    ZIO
      .scoped(client.run(request).toScopedZIO)
      .map(_.status)
  }

  def updateUser(
      updateUserDetailsRequest: smithy.UpdateUserDetailsRequest
  )(using Encoder[smithy.UpdateUserDetailsRequest]): Task[Status] = {
    val request = Request[Task](Method.POST, externalUri / "users" / "update")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(updateUserDetailsRequest)

    ZIO
      .scoped(client.run(request).toScopedZIO)
      .map(_.status)
  }

  def upsertUserContacts(
      upsertUserContactsRequest: NonEmptyChunk[smithy.UpsertUserContactRequest]
  )(using Encoder[NonEmptyChunk[smithy.UpsertUserContactRequest]]): Task[Status] = {
    val request = Request[Task](Method.POST, externalUri / "contacts" / "upsert")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(upsertUserContactsRequest)

    ZIO
      .scoped(client.run(request).toScopedZIO)
      .map(_.status)
  }
}

object GatewayApiClient {
  // Remove stupid warning that can't execute bin/sh in distroless images
  lazy val ServiceName     = "gateway"
  lazy val ExternalPort    = 8080
  lazy val InternalPort    = 8081
  lazy val HealthPort      = 8082
  lazy val ExposedServices = Set(
    ExposedService(ServiceName, ExternalPort),
    ExposedService(ServiceName, InternalPort),
    ExposedService(ServiceName, HealthPort),
  )

  given Network[Task] = Network.forAsync[Task]

  case class GatewayApiClientConfig(externalUri: Uri, internalUri: Uri, healthUri: Uri, token: String) {

    /** @param containers
      *   Option[DockerComposeContainer] * If provided resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param externalPort
      *   Int container service external port
      * @param internalPort
      *   Int container service internal port
      * @return
      *   GatewayApiClientConfig
      */
    def adjust(
        containers: Option[DockerComposeContainer],
        serviceName: String = ServiceName,
        externalPort: Int = ExternalPort,
        internalPort: Int = InternalPort,
    ): GatewayApiClientConfig = containers match {
      case Some(containers) =>
        GatewayApiClientConfig.from(
          containers,
          serviceName,
          externalPort,
          internalPort,
        )
      case None => this
    }
  }

  object GatewayApiClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param externalPort
      *   Int container service external port
      * @param internalPort
      *   Int container service internal port
      * @param healthPort
      *   Int container health port
      * @return
      *   GatewayApiClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        externalPort: Int = ExternalPort,
        internalPort: Int = InternalPort,
        healthPort: Int = HealthPort,
        token: String = "valid-token",
    ): GatewayApiClientConfig = {
      val adjustedHost         = containers.getServiceHost(serviceName, externalPort)
      val adjustedExternalPort = containers.getServicePort(serviceName, externalPort)
      val adjustedInternalPort = containers.getServicePort(serviceName, internalPort)
      val adjustedHealthPort   = containers.getServicePort(serviceName, healthPort)

      GatewayApiClientConfig(
        Uri.unsafeFromString(s"http://$adjustedHost:$adjustedExternalPort"),
        Uri.unsafeFromString(s"http://$adjustedHost:$adjustedInternalPort"),
        Uri.unsafeFromString(s"http://$adjustedHost:$adjustedHealthPort"),
        token,
      )
    }
  }

  val live =
    ZLayer.scoped(EmberClientBuilder.default[Task].build.toScopedZIO) >>> ZLayer.fromFunction(GatewayApiClient.apply)
}
