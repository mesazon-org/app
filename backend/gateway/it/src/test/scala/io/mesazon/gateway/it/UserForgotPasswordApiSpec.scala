package io.mesazon.gateway.it

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.{GatewayClientConfig, given}
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.utils.MailHogClient.MailHogClientConfig
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserForgotPasswordApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def exposedServices =
    GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices ++ MailHogClient.ExposedServices

  case class Context(
      gatewayClient: GatewayClient,
      mailHogClient: MailHogClient,
      postgresClient: PostgreSQLTestClient,
      repositoryConfig: RepositoryConfig,
      userDetailsQueries: UserDetailsQueries,
      userOtpQueries: UserOtpQueries,
      userCredentialsQueries: UserCredentialsQueries,
      userActionAttemptQueries: UserActionAttemptQueries,
      userTokenQueries: UserTokenQueries,
      passwordService: PasswordService,
      jwtService: JwtService,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      mailHogClientConfig    = MailHogClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      mailHogClient <- ZIO
        .service[MailHogClient]
        .provide(MailHogClient.live, HttpClientZioBackend.layer(), ZLayer.succeed(mailHogClientConfig))
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
      userOtpQueries <- ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, RepositoryConfig.live, appNameLive)
      userCredentialsQueries <- ZIO
        .service[UserCredentialsQueries]
        .provide(UserCredentialsQueries.live, RepositoryConfig.live, appNameLive)
      userActionAttemptQueries <- ZIO
        .service[UserActionAttemptQueries]
        .provide(UserActionAttemptQueries.live, RepositoryConfig.live, appNameLive)
      userTokenQueries <- ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, RepositoryConfig.live, appNameLive)
      passwordService <- ZIO.service[PasswordService].provide(PasswordService.live, PasswordConfig.live, appNameLive)
      jwtService      <- ZIO
        .service[JwtService]
        .provide(
          JwtService.live,
          JwtConfig.live,
          IDGenerator.liveUUIDv7,
          TimeProvider.liveSystemUTC,
          appNameLive,
        )
    } yield Context(
      gatewayApiClient,
      mailHogClient,
      postgreSQLClient,
      repositoryConfig,
      userDetailsQueries,
      userOtpQueries,
      userCredentialsQueries,
      userActionAttemptQueries,
      userTokenQueries,
      passwordService,
      jwtService,
    )

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

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

  "User Forgot Password API" when {
    "POST /forgot/password" should {
      "successfully forgot password" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        // Should delete this action attempt when new otp or expired is sent
        val userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
          )
          .zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val forgotPasswordPostResponse =
          gatewayClient.forgotPasswordPost[smithy.InternalServerError](userDetailsRow.email).zioValue

        val userOtpRowAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowAll should have size 1

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        forgotPasswordPostResponse.code shouldBe StatusCode.Ok
        forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRowAll.head.otpID.value
        forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe (userOtpRowAll.head.expiresAt.value.getEpochSecond - instantNow.getEpochSecond) +- 1

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "successfully send forgot password with existing not expired otp and update the expired time" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          // Should not delete this action attempt for non expired otp
          val userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
          )

          postgresClient
            .executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )
            .zioValue

          val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

          postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

          val userOtpRow = arbitrarySample[UserOtpRow].copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.ForgotPassword,
            expiresAt = ExpiresAt(Instant.now.plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)),
          )

          postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

          val forgotPasswordPostResponse =
            gatewayClient.forgotPasswordPost[smithy.InternalServerError](userDetailsRow.email).zioValue

          val userOtpRowAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

          userOtpRowAll should have size 1
          userOtpRowAll.head.expiresAt should not be userOtpRow.expiresAt
          userOtpRowAll.head.updatedAt should not be userOtpRow.updatedAt
          userOtpRowAll.head shouldBe userOtpRow.copy(
            expiresAt = userOtpRowAll.head.expiresAt,
            updatedAt = userOtpRowAll.head.updatedAt,
          )

          forgotPasswordPostResponse.code shouldBe StatusCode.Ok
          forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRowAll.head.otpID.value
          forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe (userOtpRowAll.head.expiresAt.value.getEpochSecond - Instant.now
            .truncatedTo(ChronoUnit.MILLIS)
            .getEpochSecond) +- 1

          mailHogClient.readInbox().zioValue.total shouldBe 0

          val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

          userDetailsRowsAll should have size 1
          userDetailsRowsAll.head shouldBe userDetailsRow

          val userActionAttemptRowsAll =
            postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

          userActionAttemptRowsAll should have size 2

          val userActionAttemptRowForgotPasswordVerifyOTPResult =
            userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP).head
          userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe userActionAttemptRowForgotPasswordVerifyOTP

          val userActionAttemptRowForgotPasswordResult =
            userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPassword).head
          userActionAttemptRowForgotPasswordResult shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowForgotPasswordResult.actionAttemptID,
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPassword,
            attempts = Attempts.assume(1),
            createdAt = userActionAttemptRowForgotPasswordResult.createdAt,
            updatedAt = userActionAttemptRowForgotPasswordResult.updatedAt,
          )

          val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

          userTokenRowsAll shouldBe empty

          val userCredentialsRowsAll =
            postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

          userCredentialsRowsAll should have size 1
          userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "successfully send forgot password with existing non expired otp and maxed attempts" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        // Should not delete this action attempt
        val userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
          )
          .zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.ForgotPassword,
          expiresAt = ExpiresAt(Instant.now.plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val userActionAttemptRow = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPassword,
          attempts = Attempts.assume(20),
        )

        postgresClient
          .executeQuery(userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow))
          .zioValue

        val forgotPasswordPostResponse =
          gatewayClient.forgotPasswordPost[smithy.InternalServerError](userDetailsRow.email).zioValue

        val userOtpRowAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowAll should have size 1
        userOtpRowAll.head shouldBe userOtpRow

        forgotPasswordPostResponse.code shouldBe StatusCode.Ok
        forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRow.otpID.value
        forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf default value

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 2

        val userActionAttemptRowForgotPasswordVerifyOTPResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP).head
        userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe userActionAttemptRowForgotPasswordVerifyOTP

        val userActionAttemptRowForgotPasswordResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPassword).head
        userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRow.copy(
          attempts = Attempts.assume(21),
          updatedAt = userActionAttemptRowForgotPasswordResult.updatedAt,
        )

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "successfully send forgot password with non existing user for that email" in withContext { context =>
        import context.*

        val email = arbitrarySample[Email]

        val forgotPasswordPostResponse =
          gatewayClient.forgotPasswordPost[smithy.InternalServerError](email).zioValue

        forgotPasswordPostResponse.code shouldBe StatusCode.Ok
        forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf default value

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll shouldBe empty
      }

      "fail with BadRequest ValidationError when request is not valid format" in withContext { context =>
        import context.*

        val forgotPasswordPostResponse =
          gatewayClient.forgotPasswordPost[smithy.ValidationError](Email.assume("invalid-email")).zioValue

        forgotPasswordPostResponse.code shouldBe StatusCode.BadRequest
        forgotPasswordPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("email"))

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }

      "fail with Unauthorized when user is not allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStage =
          Random.shuffle(OnboardStage.values.toList.diff(OnboardStage.forgotPasswordAllowedStages)).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val forgotPasswordPostResponse =
          gatewayClient.forgotPasswordPost[smithy.Unauthorized](userDetailsRow.email).zioValue

        forgotPasswordPostResponse.code shouldBe StatusCode.Unauthorized
        forgotPasswordPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }
    }

    "POST /forgot/password/verify-otp" should {
      "successfully verify otp and generate reset password token" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        // Should delete this action attempt when otp is verified
        val userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPassword,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
          )
          .zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.ForgotPassword,
          expiresAt = ExpiresAt(Instant.now.plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val forgotPasswordVerifyOTPPostResponse =
          gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.InternalServerError](
              userOtpRow.otpID,
              userOtpRow.otp,
            )
            .zioValue

        forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.Ok
        forgotPasswordVerifyOTPPostResponse.body.value.resetPasswordToken should not be empty
        forgotPasswordVerifyOTPPostResponse.body.value.resetPasswordTokenExpiresInSeconds shouldBe (2.minutes.toSeconds +- 1) // application.conf default value

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1
        userTokenRowsAll.head.userID shouldBe userDetailsRow.userID
        userTokenRowsAll.head.tokenType shouldBe TokenType.ResetPasswordToken

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "fail with BadRequest ValidationError when request is not valid format" in withContext { context =>
        import context.*

        val otpID = arbitrarySample[OtpID]

        val forgotPasswordVerifyOTPPostResponse =
          gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.ValidationError](
              otpID = otpID,
              otp = Otp.assume("invalid-otp"),
            )
            .zioValue

        forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
        forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("otp"))

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }

      "fail with BadRequest when otp is wrong" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        // Should not delete this action attempt when otp is wrong
        val userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPassword,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
          )
          .zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.ForgotPassword,
          expiresAt = ExpiresAt(Instant.now.plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val forgotPasswordVerifyOTPPostResponse =
          gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.BadRequest](
              userOtpRow.otpID,
              Otp.assume("111AAA"), // wrong otp
            )
            .zioValue

        forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
        forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.BadRequest()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll.head shouldBe userOtpRow

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 2

        val userActionAttemptRowForgotPasswordResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPassword).head
        userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRowForgotPassword

        val userActionAttemptRowForgotPasswordVerifyOTPResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP).head
        userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe UserActionAttemptRow(
          actionAttemptID = userActionAttemptRowForgotPasswordVerifyOTPResult.actionAttemptID,
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
          attempts = Attempts.assume(1),
          createdAt = userActionAttemptRowForgotPasswordVerifyOTPResult.createdAt,
          updatedAt = userActionAttemptRowForgotPasswordVerifyOTPResult.updatedAt,
        )

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "fail with Unauthorized when otp is expired" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        // Should not delete this action attempt when otp is wrong
        val userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPassword,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
          )
          .zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.ForgotPassword,
          expiresAt = ExpiresAt(Instant.now.minusSeconds(100).truncatedTo(ChronoUnit.MILLIS)),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val forgotPasswordVerifyOTPPostResponse =
          gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.Unauthorized](
              userOtpRow.otpID,
              userOtpRow.otp,
            )
            .zioValue

        forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.Unauthorized
        forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 2

        val userActionAttemptRowForgotPasswordResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPassword).head
        userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRowForgotPassword

        val userActionAttemptRowForgotPasswordVerifyOTPResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP).head
        userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe UserActionAttemptRow(
          actionAttemptID = userActionAttemptRowForgotPasswordVerifyOTPResult.actionAttemptID,
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
          attempts = Attempts.assume(1),
          createdAt = userActionAttemptRowForgotPasswordVerifyOTPResult.createdAt,
          updatedAt = userActionAttemptRowForgotPasswordVerifyOTPResult.updatedAt,
        )

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "fail with BadRequest when verify otp attempts has reached the limit" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPassword,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
          )
          .zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.ForgotPassword,
          expiresAt = ExpiresAt(Instant.now.plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
          attempts = Attempts.assume(6), // application.conf
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
          )
          .zioValue

        val forgotPasswordVerifyOTPPostResponse =
          gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.BadRequest](
              userOtpRow.otpID,
              userOtpRow.otp,
            )
            .zioValue

        forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
        forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.BadRequest()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll.head shouldBe userOtpRow

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 2

        userActionAttemptRowsAll
          .filter(_.actionAttemptType == ActionAttemptType.ForgotPassword)
          .head shouldBe userActionAttemptRowForgotPassword

        val userActionAttemptRowForgotPasswordVerifyOTPResult =
          userActionAttemptRowsAll.filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP).head

        userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe userActionAttemptRowForgotPasswordVerifyOTP.copy(
          attempts = Attempts.assume(7), // attempts should be increased by 1
          updatedAt = userActionAttemptRowForgotPasswordVerifyOTPResult.updatedAt,
        )

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "fail with InternalServerError when otp id does not exist" in withContext { context =>
        import context.*

        val otpID = arbitrarySample[OtpID]

        val forgotPasswordVerifyOTPPostResponse =
          gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.InternalServerError](
              otpID = otpID,
              otp = Otp.assume("111AAA"),
            )
            .zioValue

        forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.InternalServerError
        forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.InternalServerError()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }
    }

    "POST /forgot/password/reset" should {
      "successfully reset password with valid reset password token" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val resetPasswordJwt = jwtService.generateResetPasswordToken(userDetailsRow.userID).zioValue

        val userTokenRow = arbitrarySample[UserTokenRow].copy(
          tokenID = resetPasswordJwt.tokenID,
          userID = userDetailsRow.userID,
          tokenType = TokenType.ResetPasswordToken,
          expiresAt = resetPasswordJwt.expiresAt,
        )

        postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow)).zioValue

        val passwordNew = arbitrarySample[Password]

        val forgotPasswordResetPostResponse =
          gatewayClient
            .forgotPasswordResetPost[smithy.InternalServerError](
              resetPasswordJwt.resetPasswordToken,
              passwordNew,
            )
            .zioValue

        forgotPasswordResetPostResponse.code shouldBe StatusCode.NoContent

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head.passwordHash should not be userCredentialsRow.passwordHash
        userCredentialsRowsAll.head shouldBe userCredentialsRow.copy(
          passwordHash = userCredentialsRowsAll.head.passwordHash,
          updatedAt = userCredentialsRowsAll.head.updatedAt,
        )
      }

      "fail with BadRequest ValidationError when request is not valid format" in withContext { context =>
        import context.*

        val userID           = arbitrarySample[UserID]
        val resetPasswordJwt = jwtService.generateResetPasswordToken(userID).zioValue

        val forgotPasswordResetPostResponse =
          gatewayClient
            .forgotPasswordResetPost[smithy.ValidationError](
              resetPasswordToken = resetPasswordJwt.resetPasswordToken,
              password = Password.assume("short"),
            )
            .zioValue

        forgotPasswordResetPostResponse.code shouldBe StatusCode.BadRequest
        forgotPasswordResetPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("password"))

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }

      "fail with InternalServerError when reset password token is not found" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.forgotPasswordAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val resetPasswordJwt = jwtService.generateResetPasswordToken(userDetailsRow.userID).zioValue

        val passwordNew = arbitrarySample[Password]

        val forgotPasswordResetPostResponse =
          gatewayClient
            .forgotPasswordResetPost[smithy.InternalServerError](
              resetPasswordToken = resetPasswordJwt.resetPasswordToken,
              password = passwordNew,
            )
            .zioValue

        forgotPasswordResetPostResponse.code shouldBe StatusCode.InternalServerError
        forgotPasswordResetPostResponse.body.left.value shouldBe smithy.InternalServerError()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "fail with Unauthorized when user is not allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStage =
          Random.shuffle(OnboardStage.values.toList.diff(OnboardStage.forgotPasswordAllowedStages)).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val resetPasswordJwt = jwtService.generateResetPasswordToken(userDetailsRow.userID).zioValue

        val passwordNew = arbitrarySample[Password]

        val forgotPasswordResetPostResponse =
          gatewayClient
            .forgotPasswordResetPost[smithy.Unauthorized](
              resetPasswordToken = resetPasswordJwt.resetPasswordToken,
              password = passwordNew,
            )
            .zioValue

        forgotPasswordResetPostResponse.code shouldBe StatusCode.Unauthorized
        forgotPasswordResetPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head shouldBe userCredentialsRow
      }

      "fail with InternalServerError when user is not found" in withContext { context =>
        import context.*

        val resetPasswordJwt = jwtService.generateResetPasswordToken(arbitrarySample[UserID]).zioValue

        val passwordNew = arbitrarySample[Password]

        val forgotPasswordResetPostResponse =
          gatewayClient
            .forgotPasswordResetPost[smithy.InternalServerError](
              resetPasswordToken = resetPasswordJwt.resetPasswordToken,
              password = passwordNew,
            )
            .zioValue

        forgotPasswordResetPostResponse.code shouldBe StatusCode.InternalServerError
        forgotPasswordResetPostResponse.body.left.value shouldBe smithy.InternalServerError()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }
    }
  }
}
