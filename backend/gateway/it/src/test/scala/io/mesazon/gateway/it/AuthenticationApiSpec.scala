package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.AuthenticationApiSpec.Context
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.*
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
      postgresClient.truncateTable("local_schema", "user_otp").zioValue
    }
  }

  "Authentication" when {
    "/signup/email" should {
      "successfully sign up a new user with valid email" in withContext { context =>
        import context.*

        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.Ok

        val userOnboardRows = postgresClient.executeQuery(userManagementQueries.getAllUserOnboardRows).zioValue

        userOnboardRows should have size 1

        val userOnboardRow = userOnboardRows.head

        val expectedUserOnboardRow = UserOnboardRow(
          userID = userOnboardRow.userID,
          email = email,
          fullName = None,
          passwordHash = None,
          phoneNumber = None,
          stage = OnboardStage.EmailVerification,
          createdAt = userOnboardRow.createdAt,
          updatedAt = userOnboardRow.updatedAt,
        )

        userOnboardRow shouldBe expectedUserOnboardRow

        val userOtpRows = postgresClient.executeQuery(userManagementQueries.getAllUserOtpRows).zioValue

        userOtpRows should have size 1

        val userOtpRow = userOtpRows.head

        val expectedUserOtpRow = UserOtpRow(
          otpID = OtpID.assume(response.body.value.otpID),
          userID = userOnboardRow.userID,
          otp = userOtpRow.otp,
          otpType = OtpType.EmailVerification,
          createdAt = userOtpRow.createdAt,
          updatedAt = userOtpRow.updatedAt,
          expiresAt = userOtpRow.expiresAt,
        )

        userOtpRow shouldBe expectedUserOtpRow
      }

      "successfully re-sign up a user already seen user with stages before completion" in withContext { context =>
        import context.*

        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response1 = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response1.code shouldBe StatusCode.Ok

        val response2 = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response2.code shouldBe StatusCode.Ok

        val userOnboardRows = postgresClient.executeQuery(userManagementQueries.getAllUserOnboardRows).zioValue

        userOnboardRows should have size 1

        val userOnboardRow = userOnboardRows.head

        val expectedUserOnboardRow = UserOnboardRow(
          userID = userOnboardRow.userID,
          email = email,
          fullName = None,
          passwordHash = None,
          phoneNumber = None,
          stage = OnboardStage.EmailVerification,
          createdAt = userOnboardRow.createdAt,
          updatedAt = userOnboardRow.updatedAt,
        )

        userOnboardRow shouldBe expectedUserOnboardRow

        val userOtpRows = postgresClient.executeQuery(userManagementQueries.getAllUserOtpRows).zioValue

        userOtpRows should have size 1

        val userOtpRow = userOtpRows.head

        val expectedUserOtpRow = UserOtpRow(
          otpID = OtpID.assume(response2.body.value.otpID),
          userID = userOnboardRow.userID,
          otp = userOtpRow.otp,
          otpType = OtpType.EmailVerification,
          createdAt = userOtpRow.createdAt,
          updatedAt = userOtpRow.updatedAt,
          expiresAt = userOtpRow.expiresAt,
        )

        userOtpRow shouldBe expectedUserOtpRow
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest].copy(email = "invalidemail")

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.BadRequest
      }
    }

    "/verify/email" should {
      "successfully verify email with valid OTP" in withContext { context =>
        import context.*

        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.Ok

        val userOtpRow = postgresClient
          .executeQuery(userManagementQueries.getUserOtp(OtpID.assume(response.body.value.otpID)))
          .zioValue

        gatewayClient
          .verifyEmail(
            smithy.VerifyEmailRequest(
              otpID = userOtpRow.value.otpID.value,
              otp = userOtpRow.value.otp.value,
            )
          )
          .zioValue
          .code shouldBe StatusCode.Ok

        val userOtpRows = postgresClient.executeQuery(userManagementQueries.getAllUserOtpRows).zioValue

        userOtpRows shouldBe empty
      }

      "fail with BadRequest to verify email with invalid OTP" in withContext { context =>
        import context.*

        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.Ok

        gatewayClient
          .verifyEmail(
            smithy.VerifyEmailRequest(
              otpID = "invalidotpID",
              otp = "invalidotp",
            )
          )
          .zioValue
          .code shouldBe StatusCode.BadRequest
      }

      "fail with Unauthorized to verify email with already used OTP" in withContext { context =>
        import context.*

        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.Ok

        val userOtpRow = postgresClient
          .executeQuery(userManagementQueries.getUserOtp(OtpID.assume(response.body.value.otpID)))
          .zioValue

        gatewayClient
          .verifyEmail(
            smithy.VerifyEmailRequest(
              otpID = userOtpRow.value.otpID.value,
              otp = userOtpRow.value.otp.value,
            )
          )
          .zioValue
          .code shouldBe StatusCode.Ok

        gatewayClient
          .verifyEmail(
            smithy.VerifyEmailRequest(
              otpID = userOtpRow.value.otpID.value,
              otp = userOtpRow.value.otp.value,
            )
          )
          .zioValue
          .code shouldBe StatusCode.Unauthorized
      }

      "fail with Unauthorized to verify email with wrong OTP" in withContext { context =>
        import context.*

        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.Ok

        val userOtpRow = postgresClient
          .executeQuery(userManagementQueries.getUserOtp(OtpID.assume(response.body.value.otpID)))
          .zioValue

        gatewayClient
          .verifyEmail(
            smithy.VerifyEmailRequest(
              otpID = userOtpRow.value.otpID.value,
              otp = "AAA111",
            )
          )
          .zioValue
          .code shouldBe StatusCode.Unauthorized
      }

      "fail with Unauthorized when otp id doesn't exist" in withContext { context =>
        import context.*

        val otpID              = arbitrarySample[OtpID]
        val email              = Email.assume("email@gmai.com")
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
          .copy(email = email.value)

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.Ok

        postgresClient.executeQuery(userManagementQueries.getAllUserOnboardRows).zioValue

        val maybeUserOtpRow = postgresClient
          .executeQuery(userManagementQueries.getUserOtp(OtpID.assume(response.body.value.otpID)))
          .zioValue

        gatewayClient
          .verifyEmail(
            smithy.VerifyEmailRequest(
              otpID = otpID.value,
              otp = maybeUserOtpRow.value.otp.value,
            )
          )
          .zioValue
          .code shouldBe StatusCode.Unauthorized
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
