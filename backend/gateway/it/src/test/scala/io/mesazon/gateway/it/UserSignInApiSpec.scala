package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import com.github.plokhotnyuk.jsoniter_scala.core.given
import io.mesazon.gateway.smithy
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.smithy.{SignInResponse, ValidationError}
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import sttp.client4.{Response, ResponseException}
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
    "/signing" should {
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

        val signInResponse = gatewayClient.signIn[smithy.InternalServerError](userDetailsRow.email, password).zioValue

        signInResponse.code shouldBe StatusCode.Ok
        signInResponse.body.value.onboardStage shouldBe onboardStageFromDomainToSmithy(onboardStage)

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
          val signInResponse = gatewayClient.signIn[smithy.InternalServerError](userDetailsRow.email, password).zioValue

          signInResponse.code shouldBe StatusCode.Ok
          signInResponse.body.value.onboardStage shouldBe onboardStageFromDomainToSmithy(onboardStage)

          val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

          userTokenRowsAll should have size 2

          all(userTokenRowsAll.map(_.tokenType)) shouldBe TokenType.RefreshToken
      }

      "successfully sign in user with valid credentials after retries of failure" in withContext { context =>
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

        gatewayClient.signIn[smithy.Unauthorized](userDetailsRow.email, wrongPassword).zioValue
        gatewayClient.signIn[smithy.Unauthorized](userDetailsRow.email, wrongPassword).zioValue

        // Now attempt to sign in with correct password, should still succeed and reset attempts
        val signInResponse = gatewayClient.signIn(userDetailsRow.email, password).zioValue

        signInResponse.code shouldBe StatusCode.Ok
        signInResponse.body.value.onboardStage shouldBe onboardStageFromDomainToSmithy(onboardStage)

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1

        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with ValidationError if email is invalid" in withContext { context =>
        import context.*

        val invalidEmail = "invalid-email-format"
        val password     = arbitrarySample[Password]

        val signInResponse: Response[Either[ResponseException[ValidationError], SignInResponse]] = gatewayClient.signIn[smithy.ValidationError](invalidEmail, password).zioValue

        signInResponse.code shouldBe StatusCode.BadRequest
        signInResponse.body.left.value. shouldBe "ValidationError"
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

        val signInResponse = gatewayClient.signIn(userDetailsRow.email, wrongPassword).zioValue

        signInResponse.code shouldBe StatusCode.Unauthorized

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 0
      }
    }
  }
}
