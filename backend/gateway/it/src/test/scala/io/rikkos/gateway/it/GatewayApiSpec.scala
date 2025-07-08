package io.rikkos.gateway.it

import fs2.io.net.Network
import io.rikkos.domain.*
import io.rikkos.gateway.it.GatewayApiSpec.Context
import io.rikkos.gateway.it.client.GatewayApiClient
import io.rikkos.gateway.it.client.GatewayApiClient.GatewayApiClientConfig
import io.rikkos.gateway.it.domain.{OnboardUserDetailsRequest, UpdateUserDetailsRequest}
import io.rikkos.gateway.query.UserDetailsQueries
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import zio.*
import zio.interop.catz.*

class GatewayApiSpec extends ZWordSpecBase with DockerComposeBase with GatewayArbitraries {

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

  override def beforeEach(): Unit = withContext { case Context(_, postgresSQLClient) =>
    super.beforeEach()

    // Truncate the table before each test to ensure a clean state
    eventually(
      postgresSQLClient.truncateTable("local_schema", "users_details").zioValue
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

      "fail with BadRequest when onboarding user details are invalid" in withContext { case Context(gatewayClient, _) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]
          .copy(firstName = FirstName.assume(""))

        gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }

      "fail with Conflict when onboarding user details insert user twice" in withContext {
        case Context(gatewayClient, _) =>
          val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]

          gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent

          gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.Conflict
      }
    }

    "/users/update" should {
      "return successfully when update user" in withContext { case Context(gatewayClient, postgresSQLClient) =>
        val onboardUserDetailsRequest = arbitrarySample[OnboardUserDetailsRequest]
        val updateUserDetailsRequest  = arbitrarySample[UpdateUserDetailsRequest]

        // TODO: update when tokens contain userID and email generated from each test
        gatewayClient.userOnboard(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent

        val oldUserDetailsTable = postgresSQLClient.database
          .transactionOrDie(
            UserDetailsQueries.getUserDetailsQuery(UserID.assume("test"))
          )
          .zioValue
          .value

        gatewayClient.userUpdate(updateUserDetailsRequest).zioValue shouldBe Status.NoContent

        val updatedUserDetailsTable = postgresSQLClient.database
          .transactionOrDie(
            UserDetailsQueries.getUserDetailsQuery(UserID.assume("test"))
          )
          .zioValue
          .value

        val expectedUserDetailsTable = oldUserDetailsTable.copy(
          firstName = updateUserDetailsRequest.firstName.getOrElse(oldUserDetailsTable.firstName),
          lastName = updateUserDetailsRequest.lastName.getOrElse(oldUserDetailsTable.lastName),
          phoneRegion = updateUserDetailsRequest.phoneRegion.getOrElse(oldUserDetailsTable.phoneRegion),
          phoneNationalNumber =
            updateUserDetailsRequest.phoneNationalNumber.getOrElse(oldUserDetailsTable.phoneNationalNumber),
          addressLine1 = updateUserDetailsRequest.addressLine1.getOrElse(oldUserDetailsTable.addressLine1),
          addressLine2 = updateUserDetailsRequest.addressLine2.orElse(oldUserDetailsTable.addressLine2),
          city = updateUserDetailsRequest.city.getOrElse(oldUserDetailsTable.city),
          postalCode = updateUserDetailsRequest.postalCode.getOrElse(oldUserDetailsTable.postalCode),
          company = updateUserDetailsRequest.company.getOrElse(oldUserDetailsTable.company),
          updatedAt = updatedUserDetailsTable.updatedAt, // This should be updated to the current time
        )

        updatedUserDetailsTable shouldBe expectedUserDetailsTable
      }

      "fail with BadRequest when update user details are invalid" in withContext { case Context(gatewayClient, _) =>
        val updateUserDetailsRequest = arbitrarySample[UpdateUserDetailsRequest]
          .copy(firstName = Some(FirstName.assume("")))

        gatewayClient.userUpdate(updateUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }
    }
  }
}

object GatewayApiSpec {

  final case class Context(gatewayClient: GatewayApiClient, postgresSQLClient: PostgreSQLTestClient)
}
