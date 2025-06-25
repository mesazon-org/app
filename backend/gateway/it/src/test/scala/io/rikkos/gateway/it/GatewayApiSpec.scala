package io.rikkos.gateway.it

import fs2.io.net.Network
import io.rikkos.domain.*
import io.rikkos.gateway.it.GatewayApiSpec.Context
import io.rikkos.gateway.it.client.GatewayApiClient
import io.rikkos.gateway.it.client.GatewayApiClient.GatewayApiClientConfig
import io.rikkos.gateway.it.domain.OnboardUserDetailsRequest
import io.rikkos.gateway.query.UserDetailsQueries
import io.rikkos.gateway.smithy.UpdateUserDetailsRequest
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import io.rikkos.testkit.base.IronRefinedTypeArbitraries.given_Arbitrary_WrappedType
import io.scalaland.chimney.dsl.*
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
      gatewayApiClient <- ZIO
        .service[GatewayApiClient]
        .provide(GatewayApiClient.live, ZLayer.succeed(gatewayApiClientConfig))
    } yield Context(gatewayApiClient, postgreSQLClient)

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { case Context(gatewayClient, _) =>
    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually(
      gatewayClient.readiness.zioValue shouldBe Status.NoContent
    )
  }

  "GatewayApi" when {
    "/users/onboard" should {
      "return successfully when onboarding user" in withContext { case Context(gatewayClient, postgresSQLClient) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]

        gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent

        val userDetailsTableResponse = postgresSQLClient.database
          .transactionOrDie(
            UserDetailsQueries.getUserDetailsQuery(UserID.assume("test"))
          )
          .zioValue
          .value

        userDetailsTableResponse shouldBe onboardUserDetailsRequest
          .into[UserDetailsTable]
          .withFieldConst(_.userID, UserID.assume("test"))
          .withFieldConst(_.email, Email.assume("eliot.martel@gmail.com"))
          .withFieldConst(_.createdAt, userDetailsTableResponse.createdAt)
          .withFieldConst(_.updatedAt, userDetailsTableResponse.updatedAt)
          .transform
      }

      //fail scenario when user doesnt exist ???

      "fail with BadRequest when onboarding user details are invalid" in withContext { case Context(gatewayClient, _) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]
          .copy(firstName = FirstName.assume(""))

        gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }

      "fail with Conflict when onboarding user details insert user twice" in withContext {
        case Context(gatewayClient, _) =>
          val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]

          gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.Conflict
      }
    }
    "/users/update" should {
      "return successfully when update user" in withContext { case Context(gatewayClient, postgresSQLClient) =>
        val updateUserDetailsRequest = arbitrarySample[UpdateUserDetailsRequest]

        gatewayClient.userUpdate(updateUserDetailsRequest).zioValue shouldBe Status.NoContent

        val userDetailsTableResponse = postgresSQLClient.database
          .transactionOrDie(
            UserDetailsQueries.getUserDetailsQuery(UserID.assume("test"))
          )
          .zioValue
          .value

        userDetailsTableResponse shouldBe updateUserDetailsRequest
          .into[UserDetailsTable]
          .withFieldConst(_.userID, UserID.assume("test"))
          .withFieldConst(_.email, Email.assume("eliot.martel@gmail.com"))
          .withFieldConst(_.createdAt, userDetailsTableResponse.createdAt)
          .withFieldConst(_.updatedAt, userDetailsTableResponse.updatedAt)
          .transform

    }
      //fail scenario when user doesnt exist???
  }
}

object GatewayApiSpec {

  final case class Context(gatewayClient: GatewayApiClient, postgresSQLClient: PostgreSQLTestClient)
}
