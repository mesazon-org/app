package io.mesazon.gateway.it

import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.AuthenticationApiSpec.Context
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import sttp.model.StatusCode
import zio.*

class AuthenticationApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices = GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      userManagementQueries <- ZIO
        .service[UserManagementQueries]
        .provide(UserManagementQueries.live, RepositoryConfig.live, appNameLive)
    } yield Context(gatewayApiClient, postgreSQLClient, userManagementQueries)

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually(
      gatewayClient.readiness.zioValue shouldBe StatusCode.NoContent
    )
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    // Truncate the table before each test to ensure a clean state
    eventually {
      postgresClient.truncateTable("local_schema", "user_onboard").zioValue
    }
  }

  "Authentication" when {
    "/signup/email" should {
      "successfully sign up a user with valid email" in withContext { context =>
        import context.*

        val validEmail         = "email@gmai.com"
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = validEmail)

        gatewayClient.signUpEmail(signUpEmailRequest).zioValue shouldBe StatusCode.NoContent
      }

      "successfully sign up a user already registered to the platform" in withContext { context =>
        import context.*

        val validEmail         = "email@gmai.com"
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = validEmail)

        gatewayClient.signUpEmail(signUpEmailRequest).zioValue shouldBe StatusCode.NoContent
        gatewayClient.signUpEmail(signUpEmailRequest).zioValue shouldBe StatusCode.NoContent
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest].copy(email = "invalidemail")

        gatewayClient.signUpEmail(signUpEmailRequest).zioValue shouldBe StatusCode.BadRequest
      }
    }
  }

}

object AuthenticationApiSpec {
  case class Context(
      gatewayClient: GatewayClient,
      postgresClient: PostgreSQLTestClient,
      userManagementQueries: UserManagementQueries,
  )
}
