package io.mesazon.gateway.it.harness

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.gateway.it.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.utils.MailHogClient
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.s3.S3TestClient
import io.mesazon.testkit.base.DockerComposeBase
import org.scalatest.Suite

class GatewayAcceptanceSpec extends Suite, DockerComposeBase {

  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new UserTokenRefreshApiSpec(withContainers(identity)),
    new OrganizationManagementApiSpec(withContainers(identity)),
    new UserSignInApiSpec(withContainers(identity)),
    new FileApiSpec(withContainers(identity)),
    new UserSignUpApiSpec(withContainers(identity)),
    new UserForgotPasswordApiSpec(withContainers(identity)),
    new UserOnboardApiSpec(withContainers(identity)),
    new CustomerBookFileApiSpec(withContainers(identity)),
    new CatalogueFileApiSpec(withContainers(identity)),
    new CatalogueApiSpec(withContainers(identity)),
    new CustomerBookApiSpec(withContainers(identity)),
  )

  override def exposedServices: Set[ExposedService] =
    GatewayClient.ExposedServices ++
      PostgreSQLTestClient.ExposedServices ++
      MailHogClient.ExposedServices ++
      S3TestClient.ExposedServices
}
