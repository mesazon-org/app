package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.TwilioClient
import io.mesazon.gateway.config.TwilioClientConfig
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.*
import io.mesazon.wiremock.WiremockClient
import io.mesazon.wiremock.WiremockClient.WiremockClientConfig
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.StatusCode
import zio.*

class TwilioClientSpec extends ZWordSpecBase, SmithyArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/wiremock.yaml"

  override def exposedServices: Set[ExposedService] = WiremockClient.ExposedServices

  case class Context(twilioClientConfig: TwilioClientConfig, wiremockClient: WiremockClient)

  def withContext[A](f: Context => A): A = withContainers { container =>
    val wiremockClientConfig = WiremockClientConfig.from(container)
    val wiremockClient       = ZIO
      .service[WiremockClient]
      .provide(
        WiremockClient.live,
        ZLayer.succeed(wiremockClientConfig),
        HttpClientZioBackend.layer(),
      )
      .zioValue

    val twilioClientConfig = TwilioClientConfig(
      scheme = "http",
      host = wiremockClientConfig.host,
      port = wiremockClientConfig.port,
      accountSid = "account-sid",
      authToken = "auth-token",
      phoneNumber = "+1234567890",
    )

    f(Context(twilioClientConfig, wiremockClient))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      val healthCheckStatusResponse = wiremockClient.healthCheck.zioValue

      healthCheckStatusResponse.code shouldBe StatusCode.Ok
      healthCheckStatusResponse.body.status shouldBe "healthy"
    }
  }

  override def afterEach(): Unit = withContext { context =>
    import context.*

    super.afterEach()

    eventually {
      wiremockClient.reset.zioValue.code shouldBe StatusCode.Ok
    }
  }

  "TwilioClient" when {
    "sendOtpSms" should {
      "successfully send OTP SMS" in withContext { context =>
        import context.*

        val phoneNumber = PhoneNumberE164.assume("+1234567890")
        val otp         = Otp.assume("123ABC")

        val twilioClient = ZIO
          .service[TwilioClient]
          .provide(
            TwilioClient.live,
            ZLayer.succeed(twilioClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        twilioClient.sendOtpSms(phoneNumber, otp).zioValue

        val requestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        requestMappings.size shouldBe 1

        requestMappings(0).mapping.method shouldBe "POST"
        requestMappings(0).mapping.url shouldBe "/2010-04-01/Accounts/account-sid/Messages.json"
        requestMappings(0).count shouldBe 1
      }
    }
  }
}
