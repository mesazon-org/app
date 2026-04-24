package io.mesazon.gateway.it

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.JwtService
import io.mesazon.gateway.config.{JwtConfig, RepositoryConfig}
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.{UserDetailsRow, UserOtpRow}
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.MailHogClient.MailHogClientConfig
import io.mesazon.gateway.utils.{MailHogClient, RepositoryArbitraries, SmithyArbitraries}
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, IronRefinedTypeTransformer, ZWordSpecBase}
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.*
import zio.*

import java.time.Instant

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
      repositoryConfig: RepositoryConfig,
      jwtService: JwtService,
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
      jwtService <- ZIO
        .service[JwtService]
        .provide(
          JwtService.live,
          JwtConfig.live,
          TimeProvider.liveSystemUTC,
          IDGenerator.uuidGeneratorLive,
          appNameLive,
        )
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
      userCredentialsQueries <- ZIO
        .service[UserCredentialsQueries]
        .provide(UserCredentialsQueries.live, RepositoryConfig.live, appNameLive)
      userOtpQueries <- ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, RepositoryConfig.live, appNameLive)
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      repositoryConfig,
      jwtService,
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

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue
  }

  "User Onboard Api" when {
    "/onboard/password" should {
      "successfully onboard password for user with valid access token" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardPasswordStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(
            onboardPasswordRequest,
            Some(accessToken),
          )
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.Ok
        onboardPasswordResponse.body.value.onboardStage.value shouldBe "PASSWORD_PROVIDED"

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head.userID shouldBe userDetailsRow.userID
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val userID = arbitrarySample[UserID]

        val accessToken = jwtService.generateAccessToken(userID).zioValue.accessToken

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "short!"
        )

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(
            onboardPasswordRequest,
            Some(AccessToken.assume(accessToken.value)),
          )
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }

      "fail with Unauthorized when user is not incorrect onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.onboardPasswordStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(
            onboardPasswordRequest,
            Some(AccessToken.assume(accessToken.value)),
          )
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(onboardPasswordRequest, None)
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardPasswordRequest = arbitrarySample[smithy.OnboardPasswordRequest].copy(
          password = "ValidPassword123!"
        )

        val onboardPasswordResponse = gatewayClient
          .onboardPassword(onboardPasswordRequest, Some(AccessToken("invalidtoken")))
          .zioValue

        onboardPasswordResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }

    "onboard/details" should {
      "successfully onboard details for user with valid access token" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest]

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardDetailsResponse = gatewayClient
          .onboardDetails(
            onboardDetailsRequest,
            Some(accessToken),
          )
          .zioValue

        onboardDetailsResponse.code shouldBe StatusCode.Ok
        onboardDetailsResponse.body.value.onboardStage.value shouldBe "PHONE_VERIFICATION"

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head.userID shouldBe userDetailsRow.userID
        userDetailsRowsAll.head.fullName shouldBe Some(onboardDetailsRequest.fullName)
        userDetailsRowsAll.head.phoneNumber shouldBe Some(
          PhoneNumber(
            phoneRegion = Option
              .when(onboardDetailsRequest.phoneNumber.phoneCountryCode == "+44")(
                PhoneRegion.assume("GB")
              )
              .getOrElse(PhoneRegion.assume("CY")),
            phoneCountryCode = PhoneCountryCode.assume(onboardDetailsRequest.phoneNumber.phoneCountryCode),
            phoneNationalNumber = PhoneNationalNumber.assume(onboardDetailsRequest.phoneNumber.phoneNationalNumber),
            phoneNumberE164 = PhoneNumberE164.assume(
              onboardDetailsRequest.phoneNumber.phoneCountryCode + onboardDetailsRequest.phoneNumber.phoneNationalNumber
            ),
          )
        )
      }

      "fail with BadRequest when request is invalid" in withContext { context =>
        import context.*

        val userID = arbitrarySample[UserID]

        val accessToken = jwtService.generateAccessToken(userID).zioValue.accessToken

        val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest].copy(
          phoneNumber = smithy.PhoneNumberRequest(phoneNationalNumber = "invalid-phone", phoneCountryCode = "XX")
        )

        val onboardDetailsResponse = gatewayClient
          .onboardDetails(
            onboardDetailsRequest,
            Some(accessToken),
          )
          .zioValue

        onboardDetailsResponse.code shouldBe StatusCode.BadRequest

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0
      }

      "fail with Unauthorized when user is not in correct onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.onboardDetailsStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest]

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardDetailsResponse = gatewayClient
          .onboardDetails(
            onboardDetailsRequest,
            Some(accessToken),
          )
          .zioValue

        onboardDetailsResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1

        userDetailsRowsAll.head.userID shouldBe userDetailsRow.userID
        userDetailsRowsAll.head.fullName shouldBe None
        userDetailsRowsAll.head.phoneNumber shouldBe None
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest]

        val onboardDetailsResponse = gatewayClient
          .onboardDetails(onboardDetailsRequest, None)
          .zioValue

        onboardDetailsResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardDetailsRequest = arbitrarySample[smithy.OnboardDetailsRequest]

        val onboardDetailsResponse = gatewayClient
          .onboardDetails(onboardDetailsRequest, Some(AccessToken("invalidtoken")))
          .zioValue

        onboardDetailsResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }

    "onboard/verify/phone-number" should {
      "successfully verify phone number for user with valid access token and valid otp gamo" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head

        val fullName       = arbitrarySample[FullName]
        val phoneNumber    = arbitrarySample[PhoneNumber]
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          fullName = Some(fullName),
          phoneNumber = Some(phoneNumber),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(Instant.now.plusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(
            onboardVerifyPhoneNumberRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Ok
        onboardVerifyPhoneNumberResponse.body.value.onboardStage.value shouldBe "PHONE_VERIFIED"

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow.copy(
          onboardStage = OnboardStage.PhoneVerified,
          updatedAt = userDetailsRowsAll.head.updatedAt, // Ignore updatedAt in equality check
        )

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }

      "fail with Unauthorized when user is not in correct onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid,
          fullName = Some(arbitrarySample[FullName]),
          phoneNumber = Some(arbitrarySample[PhoneNumber]),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(Instant.now.plusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(
            onboardVerifyPhoneNumberRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll.head shouldBe userOtpRow
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(onboardVerifyPhoneNumberRequest, None)
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(onboardVerifyPhoneNumberRequest, Some(AccessToken("invalidtoken")))
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when OTP is wrong" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head

        val fullName       = arbitrarySample[FullName]
        val phoneNumber    = arbitrarySample[PhoneNumber]
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          fullName = Some(fullName),
          phoneNumber = Some(phoneNumber),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(Instant.now.plusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = "132ABC",
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(
            onboardVerifyPhoneNumberRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll.head shouldBe userOtpRow
      }

      "fail with Unauthorized when OTP is expired" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head

        val fullName       = arbitrarySample[FullName]
        val phoneNumber    = arbitrarySample[PhoneNumber]
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          fullName = Some(fullName),
          phoneNumber = Some(phoneNumber),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(Instant.now.minusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(
            onboardVerifyPhoneNumberRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll.head shouldBe userOtpRow
      }

      "fail with Unauthorized when OTP is missing" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).zioValue.head

        val fullName       = arbitrarySample[FullName]
        val phoneNumber    = arbitrarySample[PhoneNumber]
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          fullName = Some(fullName),
          phoneNumber = Some(phoneNumber),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberRequest]

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumber(
            onboardVerifyPhoneNumberRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }
    }
  }
}
