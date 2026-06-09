package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.{OrganizationID, OrganizationLogoFileName}
import io.mesazon.gateway.clients.OrganizationS3Client
import io.mesazon.gateway.config.OrganizationS3ClientConfig
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class OrganizationS3ClientSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  case class Context(organizationS3ClientConfig: OrganizationS3ClientConfig, s3TestClient: S3TestClient)

  def withContext[A](f: Context => A): A = withContainers { container =>
    val s3TestClientConfig = S3TestClientConfig.from(container)
    val s3TestClient       = ZIO
      .service[S3TestClient]
      .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      .zioValue
    val organizationS3ClientConfig = OrganizationS3ClientConfig(
      useMock = true,
      uri = Uri.apply(s3TestClientConfig.uri),
      region = s3TestClientConfig.region,
      accessKeyId = "access-key-id",
      secretAccessKey = "secret-access-key",
      organizationLogoBucket = "organization-logo-bucket",
      organizationLogoKeyPrefix = "organization-logo-key-prefix",
    )

    f(Context(organizationS3ClientConfig, s3TestClient))
  }

  "OrganizationS3Client" when {
    "uploadLogo" should {
      "successfully upload the logo and return the key" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val organizationLogoFileName = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes    = ZStream.fromResource("test-logo.jpeg")

        val key =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            key.value,
          )
          .runCollect
          .zioValue should contain theSameElementsInOrderAs organizationLogoBytes.runCollect.zioValue
      }
    }
  }

  trait TestContext {
    private val s3TestClientConfig = withContainers(S3TestClientConfig.from(_))

    val organizationS3ClientConfig = OrganizationS3ClientConfig(
      useMock = true,
      uri = Uri.apply(s3TestClientConfig.uri),
      region = s3TestClientConfig.region,
      accessKeyId = "access-key-id",
      secretAccessKey = "secret-access-key",
      organizationLogoBucket = "organization-logo-bucket",
      organizationLogoKeyPrefix = "organization-logo-key-prefix",
    )

    val s3TestClient = ZIO
      .service[S3TestClient]
      .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      .zioValue
  }
}
