package io.rikkos.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import fs2.io.net.Network
import io.rikkos.domain.{FirstName, UserDetails, UserID}
import io.rikkos.gateway.it.GatewayApiSpec.Context
import io.rikkos.gateway.it.client.GatewayApiClient
import io.rikkos.gateway.it.client.GatewayApiClient.GatewayApiClientConfig
import io.rikkos.gateway.it.domain.OnboardUserDetailsRequest
import io.rikkos.gateway.query.UserDetailsQueries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import zio.*
import zio.interop.catz.*

class GatewayApiSpec extends ZWordSpecBase with DockerComposeBase {

  given Network[Task] = Network.forAsync[Task]

  override def exposedServices = GatewayApiClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayApiClientConfig.from(container)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO.service[GatewayApiClient]
        .provide(GatewayApiClient.live, ZLayer.succeed(gatewayApiClientConfig))
    } yield Context(gatewayApiClient, postgreSQLClient)

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { case Context(apiClient,_) =>
    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually(
      apiClient.readiness.zioValue shouldBe Status.NoContent
    )
  }

  "GatewayApi" when {
    "/users/onboard" should {
      "return successfully when onboarding user" in withContext { case Context(apiClient, postgresSQLClient) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]

        apiClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent

        postgresSQLClient.database.transactionOrDie(UserDetailsQueries.getUserDetailsQuery(UserID.assume("test")))
        .zioValue.value shouldBe
      }

      "fail with BadRequest when onboarding user details ar invalid" in withContext { case Context(apiClient,_) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]
          .copy(firstName = FirstName.assume(""))

        apiClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }

      "fail with Conflict when onboarding user details insert user twice" in withContext { case Context(apiClient, _) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]

        apiClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.Conflict
      }
    }
  }
}

object GatewayApiSpec {

  final case class Context(apiClient: GatewayApiClient, postgresSQLClient: PostgreSQLTestClient)

