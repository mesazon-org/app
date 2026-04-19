package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.*
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

import java.time.Instant

class UserSignupApiSpec
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
      repositoryConfig: RepositoryConfig,
      mailHogClient: MailHogClient,
      userDetailsQueries: UserDetailsQueries,
      userOtpQueries: UserOtpQueries,
      userTokenQueries: UserTokenQueries,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      mailHogClientConfig    = MailHogClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
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
      userOtpQueries <- ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, RepositoryConfig.live, appNameLive)
      userTokenQueries <- ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, RepositoryConfig.live, appNameLive)
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      repositoryConfig,
      mailHogClient,
      userDetailsQueries,
      userOtpQueries,
      userTokenQueries,
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

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue
  }

  "User Signup API" when {
    "/signup/email" should {
      "successfully sign up a new user with valid email" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

        val signUpEmailResponse = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signUpEmailResponse.code shouldBe StatusCode.Ok

        mailHogClient.readInbox().zioValue.total shouldBe 1

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
            otpID = OtpID.assume(signUpEmailResponse.body.value.otpID),
            userID = userDetailsRowsAll.head.userID,
            otp = userOtpRowsAll.head.otp,
            otpType = OtpType.EmailVerification,
            createdAt = userOtpRowsAll.head.createdAt,
            updatedAt = userOtpRowsAll.head.updatedAt,
            expiresAt = userOtpRowsAll.head.expiresAt,
          )
        )
      }

      "successfully re-sign up a user already seen user with stages before completion" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]

        val signUpEmailResponse1 = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signUpEmailResponse1.code shouldBe StatusCode.Ok

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userOtpRowsAll1 = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        val signUpEmailResponse2 = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signUpEmailResponse2.code shouldBe StatusCode.Ok

        // Should remain 1 email in inbox
        mailHogClient.readInbox().zioValue.total shouldBe 1

        signUpEmailResponse2.body.value.otpID should not be signUpEmailResponse1.body.value.otpID

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
            otpID = OtpID.assume(signUpEmailResponse2.body.value.otpID),
            userID = userDetailsRowsAll.head.userID,
            otp = userOtpRowsAll2.head.otp,
            otpType = OtpType.EmailVerification,
            createdAt = userOtpRowsAll2.head.createdAt,
            updatedAt = userOtpRowsAll2.head.updatedAt,
            expiresAt = userOtpRowsAll2.head.expiresAt,
          )
        )
      }

      "sucessfuly not re sign up a user with not sign up email stages" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.signUpEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest].copy(email = userDetailsRow.email.value)

        val signUpEmailResponse = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signUpEmailResponse.code shouldBe StatusCode.Ok

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest].copy(email = "invalidemail")

        val signUpEmailResponse = gatewayClient.signUpEmail(signUpEmailRequest).zioValue

        signUpEmailResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }

    "/signup/verify/email" should {
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

        val signUpVerifyEmailRequest = arbitrarySample[smithy.SignUpVerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailResponse = gatewayClient.signUpVerifyEmail(signUpVerifyEmailRequest).zioValue

        signUpVerifyEmailResponse.code shouldBe StatusCode.Ok

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1
        userTokenRowsAll.head.userID shouldBe userDetailsRow.userID
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val signUpVerifyEmailRequest = arbitrarySample[smithy.SignUpVerifyEmailRequest].copy(
          otp = "invalidotp"
        )

        val signUpVerifyEmailResponse = gatewayClient.signUpVerifyEmail(signUpVerifyEmailRequest).zioValue

        signUpVerifyEmailResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with BadRequest when OTP is expired" in withContext { context =>
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

        val signUpVerifyEmailRequest = arbitrarySample[smithy.SignUpVerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailResponse = gatewayClient.signUpVerifyEmail(signUpVerifyEmailRequest).zioValue

        signUpVerifyEmailResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with BadRequest when OTP is wrong" in withContext { context =>
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

        val signUpVerifyEmailRequest = arbitrarySample[smithy.SignUpVerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = "123ABC", // wrong otp
        )

        val signUpVerifyEmailResponse = gatewayClient.signUpVerifyEmail(signUpVerifyEmailRequest).zioValue

        signUpVerifyEmailResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with BadRequest when otp type is in not what expected" in withContext { context =>
        import context.*

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = OnboardStage.EmailVerification
        )

        val otpTypeInvalid = Random.shuffle(OtpType.values.toList diff List(OtpType.EmailVerification)).zioValue.head

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = otpTypeInvalid,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val signUpVerifyEmailRequest = arbitrarySample[smithy.SignUpVerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailResponse = gatewayClient.signUpVerifyEmail(signUpVerifyEmailRequest).zioValue

        signUpVerifyEmailResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0

      }

      "fail with Unauthorized when user is in wrong onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.signUpVerifyEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid
        )

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val signUpVerifyEmailRequest = arbitrarySample[smithy.SignUpVerifyEmailRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailResponse = gatewayClient.signUpVerifyEmail(signUpVerifyEmailRequest).zioValue

        signUpVerifyEmailResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }
  }
}
