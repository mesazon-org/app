package io.mesazon.gateway.it

import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.repository.queries.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.MailHogClient
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.s3.S3TestClient

/** Everything the gateway acceptance specs need, built once from the single shared stack and injected into each spec by
  * [[GatewayAcceptanceSpec]]. Field names match the per-spec `Context` records the specs used before, so
  * `import context.*` keeps resolving them unchanged.
  */
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
