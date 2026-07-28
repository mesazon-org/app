package io.mesazon.gateway.it.harness

import com.dimafeng.testcontainers.DockerComposeContainer
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
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*

final case class GatewayItContext(
    gatewayClient: GatewayClient,
    postgresClient: PostgreSQLTestClient,
    mailHogClient: MailHogClient,
    s3TestClient: S3TestClient,
    repositoryConfig: RepositoryConfig,
    jwtService: JwtService,
    passwordService: PasswordService,
    userDetailsQueries: UserDetailsQueries,
    userCredentialsQueries: UserCredentialsQueries,
    userOtpQueries: UserOtpQueries,
    userTokenQueries: UserTokenQueries,
    userActionAttemptQueries: UserActionAttemptQueries,
    organizationDetailsQueries: OrganizationDetailsQueries,
    organizationUserQueries: OrganizationUserQueries,
    customerBookQueries: CustomerBookQueries,
)

object GatewayItContext {

  def build(container: DockerComposeContainer): Task[GatewayItContext] = for {
    postgreSQLClientConfig <- ZIO.succeed(PostgreSQLTestClientConfig.from(container))
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
}
