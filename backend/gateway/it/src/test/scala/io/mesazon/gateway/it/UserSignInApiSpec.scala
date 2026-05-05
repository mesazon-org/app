package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.{GatewayClientConfig, given}
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import sttp.model.*
import zio.*

class UserSignInApiSpec
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
      userDetailsQueries: UserDetailsQueries,
      userCredentialsQueries: UserCredentialsQueries,
      userActionAttemptQueries: UserActionAttemptQueries,
      userTokenQueries: UserTokenQueries,
      passwordService: PasswordService,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
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
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      repositoryConfig,
      userDetailsQueries,
      userCredentialsQueries,
      userActionAttemptQueries,
      userTokenQueries,
      passwordService,
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

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue
  }

  "User Sign In API" when {
    "/signin" should {
      "successfully sign in user with valid credentials" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(
            onboardStage = onboardStage
          )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val password     = arbitrarySample[Password]
        val passwordHash = passwordService.hashPassword(password).zioValue

        val userCredentialsRow =
          arbitrarySample[UserCredentialsRow].copy(
            userID = userDetailsRow.userID,
            passwordHash = passwordHash,
          )

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val signInPostResponse =
          gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password).zioValue

        signInPostResponse.code shouldBe StatusCode.Ok
        signInPostResponse.body.value.onboardStage shouldBe onboardStageFromDomainToSmithy(onboardStage)

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "successfully sign in user with valid credentials should create 2 refresh tokens if user already has 1 existing refresh token" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage
            )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val password     = arbitrarySample[Password]
          val passwordHash = passwordService.hashPassword(password).zioValue

          val userCredentialsRow =
            arbitrarySample[UserCredentialsRow].copy(
              userID = userDetailsRow.userID,
              passwordHash = passwordHash,
            )

          postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

          // Insert an existing refresh token for the user
          val existingRefreshTokenRow = arbitrarySample[UserTokenRow].copy(
            userID = userDetailsRow.userID,
            tokenType = TokenType.RefreshToken,
          )

          postgresClient.executeQuery(userTokenQueries.insertUserToken(existingRefreshTokenRow)).zioValue

          // Now attempt to sign in, should create a new refresh token in addition to existing one
          val signInPostResponse =
            gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password).zioValue

          signInPostResponse.code shouldBe StatusCode.Ok
          signInPostResponse.body.value.onboardStage shouldBe onboardStageFromDomainToSmithy(onboardStage)

          val userActionAttemptRowsAll =
            postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

          userActionAttemptRowsAll shouldBe empty

          val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

          userTokenRowsAll should have size 2

          all(userTokenRowsAll.map(_.tokenType)) shouldBe TokenType.RefreshToken
      }

      "successfully sign in user with valid credentials after some failed retries" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(
            onboardStage = onboardStage
          )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val password     = arbitrarySample[Password]
        val passwordHash = passwordService.hashPassword(password).zioValue

        val userCredentialsRow =
          arbitrarySample[UserCredentialsRow].copy(
            userID = userDetailsRow.userID,
            passwordHash = passwordHash,
          )

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        // Attempt to sign in with wrong password until we exceed max attempts
        val wrongPassword = Password("WrongPassword123!")

        gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword).zioValue
        gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword).zioValue

        // Now attempt to sign in with correct password, should still succeed and reset attempts
        val signInPostResponse =
          gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password).zioValue

        signInPostResponse.code shouldBe StatusCode.Ok
        signInPostResponse.body.value.onboardStage shouldBe onboardStageFromDomainToSmithy(onboardStage)

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with BadRequest when basic credentials is missing" in withContext { context =>
        import context.*

        val email    = arbitrarySample[Email]
        val password = arbitrarySample[Password]

        val signInPostResponse =
          gatewayClient.signInPost[smithy.BadRequest](email, password, addBasicAuth = false).zioValue

        signInPostResponse.code shouldBe StatusCode.BadRequest
        signInPostResponse.body.left.value shouldBe smithy.BadRequest()

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with BadRequest ValidationError if email is invalid" in withContext { context =>
        import context.*

        val invalidEmail = Email.assume("invalid-email-format")
        val password     = arbitrarySample[Password]

        val signInPostResponse = gatewayClient.signInPost[smithy.ValidationError](invalidEmail, password).zioValue

        signInPostResponse.code shouldBe StatusCode.BadRequest
        signInPostResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("email")
        )

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized when user onboardStage is not allowed" in withContext { context =>
        import context.*

        val onboardStage =
          Random.shuffle(OnboardStage.values.toList.diff(OnboardStage.signInAllowedStages)).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val password     = arbitrarySample[Password]
        val passwordHash = passwordService.hashPassword(password).zioValue

        val userCredentialsRow =
          arbitrarySample[UserCredentialsRow].copy(
            userID = userDetailsRow.userID,
            passwordHash = passwordHash,
          )

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val signInPostResponse = gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, password).zioValue

        signInPostResponse.code shouldBe StatusCode.Unauthorized
        signInPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll shouldBe empty

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized to sign in user with invalid credentials" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(
            onboardStage = onboardStage
          )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val password     = arbitrarySample[Password]
        val passwordHash = passwordService.hashPassword(password).zioValue

        val userCredentialsRow =
          arbitrarySample[UserCredentialsRow].copy(
            userID = userDetailsRow.userID,
            passwordHash = passwordHash,
          )

        postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

        val wrongPassword = Password("WrongPassword123!")

        val signInPostResponse =
          gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword).zioValue

        signInPostResponse.code shouldBe StatusCode.Unauthorized
        signInPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 1
        userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
          actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID, // ignore ID value
          userID = userDetailsRow.userID,
          actionAttemptType = ActionAttemptType.SignIn,
          attempts = Attempts.assume(1),
          createdAt = userActionAttemptRowsAll.head.createdAt, // ignore timestamp value
          updatedAt = userActionAttemptRowsAll.head.updatedAt, // ignore timestamp value
        )

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized to sign in user with correct credentials after invalid credentials max attempts has been reached" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.signInAllowedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage
            )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val password     = arbitrarySample[Password]
          val passwordHash = passwordService.hashPassword(password).zioValue

          val userCredentialsRow =
            arbitrarySample[UserCredentialsRow].copy(
              userID = userDetailsRow.userID,
              passwordHash = passwordHash,
            )

          postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow)).zioValue

          val wrongPassword = Password("WrongPassword123!")

          val maxAttempts = 10 // application.conf sign-in-attempts-max

          val signInPostResponses = List.fill(maxAttempts + 1)(
            gatewayClient
              .signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword)
              .zioValue
          )

          signInPostResponses.last.code shouldBe StatusCode.Unauthorized
          signInPostResponses.last.body.left.value shouldBe smithy.Unauthorized()

          val signInPostResponseAfterFailedAttempts = gatewayClient
            .signInPost[smithy.Unauthorized](userDetailsRow.email, password)
            .zioValue

          signInPostResponseAfterFailedAttempts.code shouldBe StatusCode.Unauthorized
          signInPostResponseAfterFailedAttempts.body.left.value shouldBe smithy.Unauthorized()

          val userActionAttemptRowsAll =
            postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

          userActionAttemptRowsAll should have size 1
          userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID, // ignore ID value
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(maxAttempts + 2),
            createdAt = userActionAttemptRowsAll.head.createdAt, // ignore timestamp value
            updatedAt = userActionAttemptRowsAll.head.updatedAt, // ignore timestamp value
          )

          val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

          userTokenRowsAll shouldBe empty
      }
    }
  }
}
