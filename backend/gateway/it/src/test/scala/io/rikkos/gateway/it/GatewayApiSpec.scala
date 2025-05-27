package io.rikkos.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import fs2.io.net.Network
import io.rikkos.domain.FirstName
import io.rikkos.gateway.it.GatewayApiSpec.Context
import io.rikkos.gateway.it.client.GatewayApiClient
import io.rikkos.gateway.it.domain.OnboardUserDetailsRequest
import io.rikkos.testkit.base.*
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import zio.*
import zio.interop.catz.*

class GatewayApiSpec extends ZWordSpecBase with DockerComposeBase {

  given Network[Task] = Network.forAsync[Task]

  override def exposedServices = GatewayApiClient.ExposedServices

  def withContext[A](f: Context => A): A = ZIO
    .scoped(withContainers { container =>
      for {
        dcContainer = container.asInstanceOf[DockerComposeContainer]
        gatewayApiClient <- GatewayApiClient.createClient(dcContainer)
      } yield f(Context(gatewayApiClient))
    })
    .zioValue

  override def beforeAll(): Unit = withContext { case Context(gatewayApiClient) =>
    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually(
      gatewayApiClient.readiness.zioValue shouldBe Status.NoContent
    )
  }

  "GatewayApi" when {
    "/users/onboard" should {
      "return successfully when onboarding user" in withContext { case Context(gatewayApiClient) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]

        gatewayApiClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent
      }

      "fail with BadRequest when onboarding user details ar invalid" in withContext { case Context(gatewayApiClient) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]
          .copy(firstName = FirstName.assume(""))

        gatewayApiClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }
    }
  }
}

object GatewayApiSpec {

  final case class Context(apiClient: GatewayApiClient)
}
