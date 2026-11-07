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
import io.mesazon.testkit.base.SttpTestBackend
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
    catalogueQueries: CatalogueQueries,
)

object GatewayItContext {

  val live: ZLayer[DockerComposeContainer, Throwable, GatewayItContext] =
    ZLayer.makeSome[DockerComposeContainer, GatewayItContext](
      ZLayer.fromFunction(GatewayItContext.apply),
      SttpTestBackend.liveScoped,
      ZLayer.fromFunction((container: DockerComposeContainer) => PostgreSQLTestClientConfig.from(container)),
      ZLayer.fromFunction((container: DockerComposeContainer) => GatewayClientConfig.from(container)),
      ZLayer.fromFunction((container: DockerComposeContainer) => MailHogClientConfig.from(container)),
      ZLayer.fromFunction((container: DockerComposeContainer) => S3TestClientConfig.from(container)),
      appNameLive,
      RepositoryConfig.live,
      PostgreSQLTestClient.liveScoped,
      GatewayClient.live,
      MailHogClient.live,
      S3TestClient.live,
      JwtService.live,
      JwtConfig.live,
      IDGenerator.liveUUIDv7,
      TimeProvider.liveSystemUTC,
      PasswordService.live,
      PasswordConfig.live,
      UserDetailsQueries.live,
      UserCredentialsQueries.live,
      UserOtpQueries.live,
      UserTokenQueries.live,
      UserActionAttemptQueries.live,
      OrganizationDetailsQueries.live,
      OrganizationUserQueries.live,
      CustomerBookQueries.live,
      CatalogueQueries.live,
    )
}
