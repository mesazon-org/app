package io.mesazon.gateway.utils

import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.mesazon.gateway.utils.MailHogClient.{MailHogClientConfig, MailHogResponse}
import sttp.client4.jsoniter.asJson
import sttp.client4.{basicRequest, Backend}
import sttp.model.Uri
import zio.*

class MailHogClient(
    mailHogClientConfig: MailHogClientConfig,
    sttpBackend: Backend[Task],
) {

  private given JsonValueCodec[MailHogResponse] = JsonCodecMaker.make[MailHogResponse]

  private val baseUri        = Uri.unsafeApply(mailHogClientConfig.host, mailHogClientConfig.servicePort)
  private val MessagesV2Path = "/api/v2/messages"
  private val MessagesV1Path = "/api/v1/messages"

  private val getEmailRequest = basicRequest
    .get(baseUri.withPath(MessagesV2Path))
    .response(asJson[MailHogResponse])

  private val deleteEmailRequest = basicRequest
    .delete(baseUri.withPath(MessagesV1Path))

  def readInbox(): Task[MailHogResponse] =
    getEmailRequest.send(sttpBackend).map(_.body).absolve

  def clearInbox(): Task[Unit] =
    deleteEmailRequest.send(sttpBackend).unit
}

object MailHogClient {
  val ServiceName     = "mailhog"
  val ServicePort     = 8025
  val SmtpPort        = 1025
  val ExposedServices = Set(
    ExposedService(ServiceName, ServicePort),
    ExposedService(ServiceName, SmtpPort),
  )

  case class MailHogResponse(total: Int)

  case class MailHogClientConfig(
      host: String = "localhost",
      servicePort: Int = 5432,
      smtpPort: Int = 5432,
  ) {

    /** @param containers
      *   Option[DockerComposeContainer] * If provided resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   MailHogClientConfig
      */
    def adjust(
        containers: Option[DockerComposeContainer],
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
        smtpPort: Int = SmtpPort,
    ): MailHogClientConfig = containers match {
      case Some(containers) =>
        MailHogClientConfig.from(
          containers,
          serviceName,
          servicePort,
          smtpPort,
        )
      case None => this
    }
  }

  object MailHogClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   MailHogClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
        smtpPort: Int = SmtpPort,
    ): MailHogClientConfig = {
      val adjustedHost        = containers.getServiceHost(serviceName, servicePort)
      val adjustedServicePort = containers.getServicePort(serviceName, servicePort)
      val adjustedSmtpPort    = containers.getServicePort(serviceName, smtpPort)

      MailHogClientConfig(adjustedHost, adjustedServicePort, adjustedSmtpPort)
    }
  }

  val live = ZLayer.derive[MailHogClient]
}
