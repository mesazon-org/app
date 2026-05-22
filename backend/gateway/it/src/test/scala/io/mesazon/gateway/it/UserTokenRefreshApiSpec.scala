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
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import sttp.model.*
import zio.*

class UserTokenRefreshApiSpec
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
      userTokenQueries: UserTokenQueries,
      jwtService: JwtService,
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
      userTokenQueries <- ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, RepositoryConfig.live, appNameLive)
      jwtService <- ZIO
        .service[JwtService]
        .provide(
          JwtService.live,
          JwtConfig.live,
          TimeProvider.liveSystemUTC,
          IDGenerator.liveUUIDv7,
          appNameLive,
        )
    } yield Context(
      gatewayApiClient,
      postgreSQLClient,
      repositoryConfig,
      userTokenQueries,
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

    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue
  }

  "User Token Refresh API" when {
    "POST /token/refresh" should {
      "successfully refresh user token with valid refresh token" in withContext { context =>
        import context.*

        val userID     = arbitrarySample[UserID]
        val refreshJwt = jwtService.generateRefreshToken(userID).zioValue

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            tokenID = refreshJwt.tokenID,
            userID = userID,
            tokenType = TokenType.RefreshToken,
          )

        postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow)).zioValue

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.InternalServerError](refreshJwt.refreshToken).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.Ok
        tokenRefreshPostResponse.body.value.accessTokenExpiresInSeconds should be > 0L

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue
        userTokenRowsAll should have size 1

        userTokenRowsAll.head.tokenID should not be refreshJwt.tokenID
        userTokenRowsAll.head.userID shouldBe userTokenRow.userID
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with BadRequest ValidationError when refresh token is missing" in withContext { context =>
        import context.*

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.ValidationError](RefreshToken.assume("")).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.BadRequest
        tokenRefreshPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("refreshToken"))

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized when refresh token is invalid" in withContext { context =>
        import context.*

        val invalidRefreshToken = RefreshToken.assume("invalid-refresh-token")

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.Unauthorized](invalidRefreshToken).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
        tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }

      "fail with Unauthorized when refresh token is valid but not found in database" in withContext { context =>
        import context.*

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            tokenType = TokenType.RefreshToken
          )

        // Note: we do not insert the user token row into database, so it will be missing when service tries to look it up
        val refreshJwt = jwtService.generateRefreshToken(userTokenRow.userID).zioValue

        val tokenRefreshPostResponse =
          gatewayClient.tokenRefreshPost[smithy.Unauthorized](refreshJwt.refreshToken).zioValue

        tokenRefreshPostResponse.code shouldBe StatusCode.Unauthorized
        tokenRefreshPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll shouldBe empty
      }
    }
  }
}
