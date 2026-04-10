package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.{RepositoryArbitraries, SmithyArbitraries}
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, IronRefinedTypeTransformer, ZWordSpecBase}
import sttp.model.*
import zio.*

import java.time.Instant

class UserSignupApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices = GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices

  case class Context(
      gatewayClient: GatewayClient,
      postgresClient: PostgreSQLTestClient,
      userDetailsQueries: UserDetailsQueries,
      userOtpQueries: UserOtpQueries,
      userTokenQueries: UserTokenQueries,
  )

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
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
      userOtpQueries <- ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, RepositoryConfig.live, appNameLive)
      userTokenQueries <- ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, RepositoryConfig.live, appNameLive)
    } yield Context(gatewayApiClient, postgreSQLClient, userDetailsQueries, userOtpQueries, userTokenQueries)

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
      postgresClient.truncateTable("local_schema", "user_details").zioValue
      postgresClient.truncateTable("local_schema", "user_otp").zioValue
      postgresClient.truncateTable("local_schema", "user_token").zioValue
    }
  }

  "User Signup API" when {
    "/signup/email" should {
      "successfully sign up a new user with valid email and verify email" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

        val signupEmailResponse = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signupEmailResponse.code shouldBe StatusCode.Ok

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1

        userDetailsRowsAll should contain theSameElementsAs List(
          UserDetailsRow(
            userID = userDetailsRowsAll.head.userID,
            email = userDetailsRowsAll.head.email,
            fullName = None,
            phoneNumber = None,
            onboardStage = OnboardStage.EmailVerification,
            createdAt = userDetailsRowsAll.head.createdAt,
            updatedAt = userDetailsRowsAll.head.updatedAt,
          )
        )

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll should contain theSameElementsAs List(
          UserOtpRow(
            otpID = OtpID.assume(signupEmailResponse.body.value.otpID),
            userID = userDetailsRowsAll.head.userID,
            otp = userOtpRowsAll.head.otp,
            otpType = OtpType.EmailVerification,
            createdAt = userOtpRowsAll.head.createdAt,
            updatedAt = userOtpRowsAll.head.updatedAt,
            expiresAt = userOtpRowsAll.head.expiresAt,
          )
        )
      }

      "successfully re-sign up a user already seen user with stages before completion and verify email" in withContext {
        context =>
          import context.*

          val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

          val signupEmailResponse1 = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

          signupEmailResponse1.code shouldBe StatusCode.Ok

          val userOtpRowsAll1 = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

          val signupEmailResponse2 = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

          signupEmailResponse2.code shouldBe StatusCode.Ok

          signupEmailResponse2.body.value.otpID should not be signupEmailResponse1.body.value.otpID

          val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

          userDetailsRowsAll should have size 1
          userDetailsRowsAll should contain theSameElementsAs List(
            UserDetailsRow(
              userID = userDetailsRowsAll.head.userID,
              email = userDetailsRowsAll.head.email,
              fullName = None,
              phoneNumber = None,
              onboardStage = OnboardStage.EmailVerification,
              createdAt = userDetailsRowsAll.head.createdAt,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
          )

          val userOtpRowsAll2 = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

          userOtpRowsAll1 should have size 1
          userOtpRowsAll2 should have size 1

          assert(userOtpRowsAll1.head.expiresAt.value.isBefore(userOtpRowsAll2.head.expiresAt.value))

          userOtpRowsAll2 should contain theSameElementsAs List(
            UserOtpRow(
              otpID = OtpID.assume(signupEmailResponse2.body.value.otpID),
              userID = userDetailsRowsAll.head.userID,
              otp = userOtpRowsAll2.head.otp,
              otpType = OtpType.EmailVerification,
              createdAt = userOtpRowsAll2.head.createdAt,
              updatedAt = userOtpRowsAll2.head.updatedAt,
              expiresAt = userOtpRowsAll2.head.expiresAt,
            )
          )
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest].copy(email = "invalidemail")

        val response = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        response.code shouldBe StatusCode.BadRequest
      }
    }

    "/verify/email" should {
      "successfully verify email with valid OTP and return user token" in withContext { context =>
        import context.*

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = OnboardStage.EmailVerification
        )
        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
          expiresAt = ExpiresAt.assume(Instant.now.plusSeconds(10)),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val verifyEmailResponse = gatewayClient.verifyEmail(verifyEmailRequest).zioValue

        verifyEmailResponse.code shouldBe StatusCode.Ok

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1
        userTokenRowsAll.head.userID shouldBe userDetailsRow.userID
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest].copy(
          otp = "invalidotp"
        )

        val response = gatewayClient.verifyEmail(verifyEmailRequest).zioValue

        response.code shouldBe StatusCode.BadRequest
      }

      "fail with Unauthorized when OTP is expired" in withContext { context =>
        import context.*

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = OnboardStage.EmailVerification
        )
        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
          expiresAt = ExpiresAt.assume(Instant.now.minusSeconds(10)),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val response = gatewayClient.verifyEmail(verifyEmailRequest).zioValue

        response.code shouldBe StatusCode.Unauthorized
      }

      "fail with Unauthorized when OTP is wrong" in withContext { context =>
        import context.*

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = OnboardStage.EmailVerification
        )
        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val verifyEmailRequest = arbitrarySample[smithy.VerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = "123ABC", // wrong otp
        )

        val response = gatewayClient.verifyEmail(verifyEmailRequest).zioValue

        response.code shouldBe StatusCode.Unauthorized
      }
    }
  }
}
