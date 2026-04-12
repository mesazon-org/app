package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.MailHogClient.MailHogClientConfig
import io.mesazon.gateway.utils.{MailHogClient, RepositoryArbitraries, SmithyArbitraries}
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, IronRefinedTypeTransformer, ZWordSpecBase}
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.*
import zio.*

class UserOnboardApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices =
    GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices ++ MailHogClient.ExposedServices

  case class Context(
      gatewayClient: GatewayClient,
      postgresClient: PostgreSQLTestClient,
      mailHogClient: MailHogClient,
      userDetailsQueries: UserDetailsQueries,
      userCredentialsQueries: UserCredentialsQueries,
      userOtpQueries: UserOtpQueries,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      mailHogClientConfig    = MailHogClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      mailHogClient <- ZIO
        .service[MailHogClient]
        .provide(MailHogClient.live, HttpClientZioBackend.layer(), ZLayer.succeed(mailHogClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
      userCredentialsQueries <- ZIO
        .service[UserCredentialsQueries]
        .provide(UserCredentialsQueries.live, RepositoryConfig.live, appNameLive)
      userOtpQueries <- ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, RepositoryConfig.live, appNameLive)
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      mailHogClient,
      userDetailsQueries,
      userCredentialsQueries,
      userOtpQueries,
    )

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

    mailHogClient.clearInbox().zioValue

    postgresClient.truncateTable("local_schema", "user_details").zioValue
    postgresClient.truncateTable("local_schema", "user_credentials").zioValue
  }

  "User Onboard Api" when {
    "/onboard/password" should {
      "successfully onboard password for user with valid access token" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

        val signupEmailResponse = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signupEmailResponse.code shouldBe StatusCode.Ok

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userOtpRow = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue.head

        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val verifyEmailResponse = gatewayClient.verifyEmail(verifyEmailRequest).zioValue

        verifyEmailResponse.code shouldBe StatusCode.Ok

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(onboardPasswordRequest, Some(AccessToken.assume(verifyEmailResponse.body.value.accessToken)))
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.Ok
        onboardPasswordResponse.body.value.onboardStage.value shouldBe "PASSWORD_PROVIDED"

        mailHogClient.readInbox().zioValue.total shouldBe 2

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head.userID shouldBe userOtpRow.userID
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

        val signupEmailResponse = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signupEmailResponse.code shouldBe StatusCode.Ok

        val userOtpRow = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue.head

        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val verifyEmailResponse = gatewayClient.verifyEmail(verifyEmailRequest).zioValue

        verifyEmailResponse.code shouldBe StatusCode.Ok

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "short!"
        )

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(onboardPasswordRequest, Some(AccessToken.assume(verifyEmailResponse.body.value.accessToken)))
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 1
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val response = gatewayClient
          .onboardPassword(onboardPasswordRequest, None)
          .zioValue

        response.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val response = gatewayClient
          .onboardPassword(onboardPasswordRequest, Some(AccessToken("invalidtoken")))
          .zioValue

        response.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }
  }
}
