package io.mesazon.gateway.it.client

import com.dimafeng.testcontainers.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fs2.io.net.Network
import io.mesazon.gateway.it.client.GatewayClient.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.smithy.SignUpEmailResponse
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.jsoniter.asJson
import sttp.model.*
import zio.*
import zio.interop.catz.*

case class GatewayClient(config: GatewayClientConfig, sttpBackend: Backend[Task]) {
  import config.*

  given JsonValueCodec[smithy.OnboardUserDetailsRequest]      = JsonCodecMaker.make
  given JsonValueCodec[smithy.UpdateUserDetailsRequest]       = JsonCodecMaker.make
  given JsonValueCodec[smithy.SignUpEmailRequest]             = JsonCodecMaker.make
  given JsonValueCodec[smithy.UpsertUserContactRequest]       = JsonCodecMaker.make
  given JsonValueCodec[smithy.VerifyEmailRequest]             = JsonCodecMaker.make
  given JsonValueCodec[List[smithy.UpsertUserContactRequest]] = JsonCodecMaker.make

  given JsonValueCodec[smithy.SignUpEmailResponse] = JsonCodecMaker.make

  def liveness: Task[StatusCode] = basicRequest
    .get(healthUri.addPath("liveness"))
    .send(sttpBackend)
    .map(_.code)

  def readiness: Task[StatusCode] = basicRequest
    .get(healthUri.addPath("readiness"))
    .send(sttpBackend)
    .map(_.code)

  def signUpEmail(
      signUpEmailRequest: smithy.SignUpEmailRequest
  ): Task[Response[Either[ResponseException[String], SignUpEmailResponse]]] =
    basicRequest
      .post(externalUri.addPath("signup", "email"))
      .body(asJson(signUpEmailRequest))
      .response(asJson[smithy.SignUpEmailResponse])
      .send(sttpBackend)

  def verifyEmail(
      verifyEmailRequest: smithy.VerifyEmailRequest
  ): Task[Response[Unit]] =
    basicRequest
      .post(externalUri.addPath("verify", "email"))
      .body(asJson(verifyEmailRequest))
      .response(ignore)
      .send(sttpBackend)

  def onboardUser(
      onboardUserDetailsRequest: smithy.OnboardUserDetailsRequest
  ): Task[StatusCode] =
    basicRequest
      .post(externalUri.addPath("users", "onboard"))
      .auth
      .bearer(token)
      .body(asJson(onboardUserDetailsRequest))
      .send(sttpBackend)
      .map(_.code)

  def updateUser(
      updateUserDetailsRequest: smithy.UpdateUserDetailsRequest
  ): Task[StatusCode] =
    basicRequest
      .post(externalUri.addPath("users", "update"))
      .auth
      .bearer(token)
      .body(asJson(updateUserDetailsRequest))
      .send(sttpBackend)
      .map(_.code)

  def upsertUserContacts(
      upsertUserContactsRequest: List[smithy.UpsertUserContactRequest]
  ): Task[StatusCode] =
    basicRequest
      .post(externalUri.addPath("contacts", "upsert"))
      .auth
      .bearer(token)
      .body(asJson(upsertUserContactsRequest))
      .send(sttpBackend)
      .map(_.code)
}

object GatewayClient {
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

  case class GatewayClientConfig(externalUri: Uri, internalUri: Uri, healthUri: Uri, token: String) {

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
    ): GatewayClientConfig = containers match {
      case Some(containers) =>
        GatewayClientConfig.from(
          containers,
          serviceName,
          externalPort,
          internalPort,
        )
      case None => this
    }
  }

  object GatewayClientConfig {

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
    ): GatewayClientConfig = {
      val adjustedHost         = containers.getServiceHost(serviceName, externalPort)
      val adjustedExternalPort = containers.getServicePort(serviceName, externalPort)
      val adjustedInternalPort = containers.getServicePort(serviceName, internalPort)
      val adjustedHealthPort   = containers.getServicePort(serviceName, healthPort)

      GatewayClientConfig(
        Uri.unsafeApply(adjustedHost, adjustedExternalPort),
        Uri.unsafeApply(adjustedHost, adjustedInternalPort),
        Uri.unsafeApply(adjustedHost, adjustedHealthPort),
        token,
      )
    }
  }

  val live = HttpClientZioBackend.layer() >>> ZLayer.fromFunction(GatewayClient.apply)
}
