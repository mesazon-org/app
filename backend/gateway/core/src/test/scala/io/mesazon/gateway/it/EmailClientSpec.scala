package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.utils.MailHogClient.MailHogClientConfig
import io.mesazon.testkit.base.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*

class EmailClientSpec extends ZWordSpecBase, SmithyArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/email.yaml"

  override def exposedServices: Set[ExposedService] = MailHogClient.ExposedServices

  case class Context(emailConfig: EmailConfig, mailHogClient: MailHogClient)

  def withContext[A](f: Context => A): A = withContainers { container =>
    val mailHogClientConfig = MailHogClientConfig.from(container)
    val mailHogClient       = ZIO
      .service[MailHogClient]
      .provide(MailHogClient.live, HttpClientZioBackend.layer(), ZLayer.succeed(mailHogClientConfig))
      .zioValue

    val emailConfig = EmailConfig(
      host = mailHogClientConfig.host,
      port = mailHogClientConfig.smtpPort,
      senderEmail = "john@doe.com",
      senderPassword = "password",
      redirectScheme = "http",
      redirectHost = "localhost",
      redirectPort = 9090,
      enableTls = false,
    )

    f(Context(emailConfig, mailHogClient))
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    mailHogClient.clearInbox().zioValue
  }

  "EmailClient" when {
    "sendEmailVerificationEmail" should {
      "send email successfully" in withContext { context =>
        import context.*

        val email = Email.assume("bar@foo.com")
        val otp   = Otp.assume("123ABC")

        val emailClient = ZIO
          .service[EmailClient]
          .provide(EmailClient.live, ZLayer.succeed(emailConfig))
          .zioValue

        emailClient.sendEmailVerificationEmail(email, otp).zioValue

        mailHogClient.readInbox().zioValue.total shouldBe 1
      }
    }

    "sendWelcomeEmail" should {
      "send email successfully" in withContext { context =>
        import context.*

        val email    = Email.assume("bar@foo.com")
        val fullName = FullName.assume("Bar Foo")

        val emailClient = ZIO
          .service[EmailClient]
          .provide(EmailClient.live, ZLayer.succeed(emailConfig))
          .zioValue

        emailClient.sendWelcomeEmail(email, fullName).zioValue

        mailHogClient.readInbox().zioValue.total shouldBe 1
      }
    }
  }
}
