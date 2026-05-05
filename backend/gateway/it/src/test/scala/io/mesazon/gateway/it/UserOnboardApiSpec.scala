package io.mesazon.gateway.it

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.{JwtConfig, RepositoryConfig}
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.{GatewayClientConfig, given}
import io.mesazon.gateway.repository.domain.{UserDetailsRow, UserOtpRow}
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.JwtService
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
import java.time.temporal.ChronoUnit

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
    "POST /onboard/password" should {
      "successfully onboard password for user with valid access token" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardPasswordStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest].copy(
          password = "ValidPassword123!"
        )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardPasswordPostResponse = gatewayClient
          .onboardPasswordPost[smithy.InternalServerError](
            onboardPasswordPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardPasswordPostResponse.code shouldBe StatusCode.Ok
        onboardPasswordPostResponse.body.value.onboardStage.name shouldBe "PASSWORD_PROVIDED"

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 1
        userCredentialsRowsAll.head.userID shouldBe userDetailsRow.userID
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        val userID = arbitrarySample[UserID]

        val accessToken = jwtService.generateAccessToken(userID).zioValue.accessToken

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest].copy(
          password = "short!"
        )

        val onboardPasswordPostResponse = gatewayClient
          .onboardPasswordPost[smithy.ValidationError](
            onboardPasswordPostRequest,
            Some(AccessToken.assume(accessToken.value)),
          )
          .zioValue

        onboardPasswordPostResponse.code shouldBe StatusCode.BadRequest
        onboardPasswordPostResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("password")
        )

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userCredentialsRowsAll =
          postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting).zioValue

        userCredentialsRowsAll should have size 0
      }

      "fail with Unauthorized when user is not in correct onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.onboardPasswordStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest].copy(
          password = "ValidPassword123!"
        )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardPasswordPostResponse = gatewayClient
          .onboardPasswordPost[smithy.Unauthorized](
            onboardPasswordPostRequest,
            Some(AccessToken.assume(accessToken.value)),
          )
          .zioValue

        onboardPasswordPostResponse.code shouldBe StatusCode.Unauthorized
        onboardPasswordPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest].copy(
          password = "ValidPassword123!"
        )

        val onboardPasswordPostResponse = gatewayClient
          .onboardPasswordPost[smithy.Unauthorized](onboardPasswordPostRequest, None)
          .zioValue

        onboardPasswordPostResponse.code shouldBe StatusCode.Unauthorized
        onboardPasswordPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest].copy(
          password = "ValidPassword123!"
        )

        val onboardPasswordPostResponse = gatewayClient
          .onboardPasswordPost[smithy.Unauthorized](onboardPasswordPostRequest, Some(AccessToken("invalidtoken")))
          .zioValue

        onboardPasswordPostResponse.code shouldBe StatusCode.Unauthorized
        onboardPasswordPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }

    "POST onboard/details" should {
      "successfully onboard details for user with valid access token" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.onboardDetailsStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage,
          phoneNumber = None,
          fullName = None,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardDetailsPostResponse = gatewayClient
          .onboardDetailsPost[smithy.InternalServerError](
            onboardDetailsPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardDetailsPostResponse.code shouldBe StatusCode.Ok
        onboardDetailsPostResponse.body.value.onboardStage.name shouldBe "PHONE_VERIFICATION"

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head.userID shouldBe userDetailsRow.userID
        userDetailsRowsAll.head.fullName shouldBe Some(onboardDetailsPostRequest.fullName)
        userDetailsRowsAll.head.phoneNumber shouldBe Some(
          PhoneNumber(
            phoneRegion = Option
              .when(onboardDetailsPostRequest.phoneNumber.phoneCountryCode == "+44")(
                PhoneRegion.assume("GB")
              )
              .getOrElse(PhoneRegion.assume("CY")),
            phoneCountryCode = PhoneCountryCode.assume(onboardDetailsPostRequest.phoneNumber.phoneCountryCode),
            phoneNationalNumber = PhoneNationalNumber.assume(onboardDetailsPostRequest.phoneNumber.phoneNationalNumber),
            phoneNumberE164 = PhoneNumberE164.assume(
              onboardDetailsPostRequest.phoneNumber.phoneCountryCode + onboardDetailsPostRequest.phoneNumber.phoneNationalNumber
            ),
          )
        )
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        val userID = arbitrarySample[UserID]

        val accessToken = jwtService.generateAccessToken(userID).zioValue.accessToken

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest].copy(
          phoneNumber = smithy.PhoneNumberRequest(phoneNationalNumber = "invalid-phone", phoneCountryCode = "XX")
        )

        val onboardDetailsPostResponse = gatewayClient
          .onboardDetailsPost[smithy.ValidationError](
            onboardDetailsPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardDetailsPostResponse.code shouldBe StatusCode.BadRequest
        onboardDetailsPostResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("phoneNationalNumber")
        )

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

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardDetailsPostResponse = gatewayClient
          .onboardDetailsPost[smithy.Unauthorized](
            onboardDetailsPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardDetailsPostResponse.code shouldBe StatusCode.Unauthorized
        onboardDetailsPostResponse.body.left.value shouldBe smithy.Unauthorized()

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

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val onboardDetailsPostResponse = gatewayClient
          .onboardDetailsPost[smithy.Unauthorized](onboardDetailsPostRequest, None)
          .zioValue

        onboardDetailsPostResponse.code shouldBe StatusCode.Unauthorized
        onboardDetailsPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]

        val onboardDetailsPostResponse = gatewayClient
          .onboardDetailsPost[smithy.Unauthorized](onboardDetailsPostRequest, Some(AccessToken("invalidtoken")))
          .zioValue

        onboardDetailsPostResponse.code shouldBe StatusCode.Unauthorized
        onboardDetailsPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }

    "POST onboard/verify/phone-number" should {
      "successfully verify phone number for user with valid access token and valid otp" in withContext { context =>
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

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.InternalServerError](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Ok
        onboardVerifyPhoneNumberResponse.body.value.onboardStage.name shouldBe "PHONE_VERIFIED"

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

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.Unauthorized](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberResponse.body.left.value shouldBe smithy.Unauthorized()

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

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.Unauthorized](onboardVerifyPhoneNumberPostRequest, None)
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.Unauthorized](
            onboardVerifyPhoneNumberPostRequest,
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberResponse.body.left.value shouldBe smithy.Unauthorized()

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

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = "132ABC",
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.Unauthorized](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberResponse.body.left.value shouldBe smithy.Unauthorized()

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

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          expiresAt = ExpiresAt(instantNow.minusSeconds(10)),
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          .copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.Unauthorized](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberResponse.body.left.value shouldBe smithy.Unauthorized()

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

        val onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

        val onboardVerifyPhoneNumberResponse = gatewayClient
          .onboardVerifyPhoneNumberPost[smithy.Unauthorized](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessToken),
          )
          .zioValue

        onboardVerifyPhoneNumberResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberResponse.body.left.value shouldBe smithy.Unauthorized()

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

    "GET /onboard/verify/phone-number" should {
      "successfully get the OTP for user with valid access token" in withContext { context =>
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

        val expiresInSeconds = 100L

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
          expiresAt = ExpiresAt(Instant.now.plusSeconds(expiresInSeconds)),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberGetResponse = gatewayClient
          .onboardVerifyPhoneNumberGet[smithy.InternalServerError](
            Some(accessToken)
          )
          .zioValue

        onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Ok
        onboardVerifyPhoneNumberGetResponse.body.value.otpID shouldBe userOtpRow.otpID.value
        onboardVerifyPhoneNumberGetResponse.body.value.otpExpiresInSeconds shouldBe (99L +- expiresInSeconds)

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

      "fail with Unauthorized when OTP is missing for user" in withContext { context =>
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

        val onboardVerifyPhoneNumberGetResponse = gatewayClient
          .onboardVerifyPhoneNumberGet[smithy.Unauthorized](
            Some(accessToken)
          )
          .zioValue

        onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }

      "fail with Unauthorized when OTP is expired for user" in withContext { context =>
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
          userID = userDetailsRow.userID,
          otpType = OtpType.PhoneVerification,
          expiresAt = ExpiresAt(Instant.now()),
        )

        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberGetResponse = gatewayClient
          .onboardVerifyPhoneNumberGet[smithy.Unauthorized](
            Some(accessToken)
          )
          .zioValue

        onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRow

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val onboardVerifyPhoneNumberGetResponse = gatewayClient
          .onboardVerifyPhoneNumberGet[smithy.Unauthorized](None)
          .zioValue

        onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

        val userOtpRowsAll =
          postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val onboardVerifyPhoneNumberGetResponse = gatewayClient
          .onboardVerifyPhoneNumberGet[smithy.Unauthorized](Some(AccessToken("invalidtoken")))
          .zioValue

        onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 0

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

        val accessToken = jwtService.generateAccessToken(userDetailsRow.userID).zioValue.accessToken

        val onboardVerifyPhoneNumberGetResponse = gatewayClient
          .onboardVerifyPhoneNumberGet[smithy.Unauthorized](
            Some(accessToken)
          )
          .zioValue

        onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
        onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.Unauthorized()

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
