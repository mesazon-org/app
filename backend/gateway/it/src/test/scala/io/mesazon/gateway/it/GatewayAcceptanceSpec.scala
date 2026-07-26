package io.mesazon.gateway.it

import com.dimafeng.testcontainers.*
import io.mesazon.clock.TimeProvider
import io.mesazon.gateway.config.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.MailHogClient
import io.mesazon.gateway.utils.MailHogClient.MailHogClientConfig
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, ZIOTestOps}
import org.scalatest.Suites
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should
import org.scalatest.time.{Minutes, Seconds, Span}
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.StatusCode
import zio.*

/** Parent of every gateway acceptance spec.
  *
  * The specs used to mix in `DockerComposeBase` individually, so each booted its own full stack (gateway + postgres +
  * flyway + mailhog + wiremock + s3mock). Here that stack is booted exactly once for the whole run: this suite extends
  * `DockerComposeBase`, nests all the specs, waits for the gateway/migrations once, builds the shared context, and
  * injects it into each nested spec. The specs are `@DoNotDiscover` and mix in [[GatewayAcceptanceTest]], so they run
  * only through this parent — sequentially against the one stack.
  */
class GatewayAcceptanceSpec
    extends Suites(
      new CustomerBookApiSpec,
      new OrganizationManagementApiSpec,
      new FileApiSpec,
      new UserOnboardApiSpec,
      new UserSignUpApiSpec,
      new UserSignInApiSpec,
      new UserForgotPasswordApiSpec,
      new UserTokenRefreshApiSpec,
    ),
      DockerComposeBase,
      should.Matchers,
      Eventually,
      ZIOTestOps {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minutes), interval = Span(2, Seconds))

  /** Union of every spec's exposed services, so the one stack serves all of them. Wiremock is not exposed: the gateway
    * talks to it over the compose network and the tests never connect to it directly.
    */
  override def exposedServices: Set[ExposedService] =
    GatewayClient.ExposedServices ++
      PostgreSQLTestClient.ExposedServices ++
      MailHogClient.ExposedServices ++
      S3TestClient.ExposedServices

  override def afterContainersStart(containers: Containers): Unit = {
    super.afterContainersStart(containers)

    val context = buildContext(containers)

    eventually(
      context.gatewayClient.readiness.zioValue shouldBe StatusCode.NoContent
    )

    eventually(
      ZIO
        .foreach(context.repositoryConfig.allTableNames)(tableName =>
          context.postgresClient.checkIfTableExists(context.repositoryConfig.schema, tableName)
        )
        .zioValue should contain only true
    )

    nestedSuites.foreach {
      case spec: GatewayAcceptanceTest => spec.setContext(context)
      case _                           => ()
    }
  }

  private def buildContext(container: DockerComposeContainer): GatewayItContext = {
    val build = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      mailHogClientConfig    = MailHogClientConfig.from(container)
      s3TestClientConfig     = S3TestClientConfig.from(container)
      repositoryConfig <- ZIO.service[RepositoryConfig].provide(RepositoryConfig.live, appNameLive)
      postgresClient   <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      mailHogClient <- ZIO
        .service[MailHogClient]
        .provide(MailHogClient.live, HttpClientZioBackend.layer(), ZLayer.succeed(mailHogClientConfig))
      s3TestClient <- ZIO
        .service[S3TestClient]
        .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      jwtService <- ZIO
        .service[JwtService]
        .provide(JwtService.live, JwtConfig.live, IDGenerator.liveUUIDv7, TimeProvider.liveSystemUTC, appNameLive)
      passwordService    <- ZIO.service[PasswordService].provide(PasswordService.live, PasswordConfig.live, appNameLive)
      userDetailsQueries <- ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, RepositoryConfig.live, appNameLive)
      userCredentialsQueries <- ZIO
        .service[UserCredentialsQueries]
        .provide(UserCredentialsQueries.live, RepositoryConfig.live, appNameLive)
      userOtpQueries <- ZIO
        .service[UserOtpQueries]
        .provide(UserOtpQueries.live, RepositoryConfig.live, appNameLive)
      userTokenQueries <- ZIO
        .service[UserTokenQueries]
        .provide(UserTokenQueries.live, RepositoryConfig.live, appNameLive)
      userActionAttemptQueries <- ZIO
        .service[UserActionAttemptQueries]
        .provide(UserActionAttemptQueries.live, RepositoryConfig.live, appNameLive)
      organizationDetailsQueries <- ZIO
        .service[OrganizationDetailsQueries]
        .provide(OrganizationDetailsQueries.live, RepositoryConfig.live, appNameLive)
      organizationUserQueries <- ZIO
        .service[OrganizationUserQueries]
        .provide(OrganizationUserQueries.live, RepositoryConfig.live, appNameLive)
      customerBookQueries <- ZIO
        .service[CustomerBookQueries]
        .provide(CustomerBookQueries.live, RepositoryConfig.live, appNameLive)
    } yield GatewayItContext(
      gatewayClient,
      postgresClient,
      mailHogClient,
      s3TestClient,
      repositoryConfig,
      jwtService,
      passwordService,
      userDetailsQueries,
      userCredentialsQueries,
      userOtpQueries,
      userTokenQueries,
      userActionAttemptQueries,
      organizationDetailsQueries,
      organizationUserQueries,
      customerBookQueries,
    )

    build.zioValue
  }
}
