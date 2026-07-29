package io.mesazon.gateway.it.harness

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.gateway.it.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.utils.MailHogClient
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.test.s3.S3TestClient
import io.mesazon.testkit.base.{DockerComposeBase, ZIOTestOps}
import org.scalatest.Suites
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should
import org.scalatest.time.{Minutes, Seconds, Span}
import sttp.model.StatusCode
import zio.*

class GatewayAcceptanceSpec
    extends Suites(
      new CatalogueApiSpec,
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

  override def exposedServices: Set[ExposedService] =
    GatewayClient.ExposedServices ++
      PostgreSQLTestClient.ExposedServices ++
      MailHogClient.ExposedServices ++
      S3TestClient.ExposedServices

  override def afterContainersStart(containers: Containers): Unit = {
    super.afterContainersStart(containers)

    val context = GatewayItContext.build(containers).zioValue

    eventually(context.gatewayClient.readiness.zioValue shouldBe StatusCode.NoContent)
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
}
