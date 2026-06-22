package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.{OrganizationID, OrganizationLogoFileName}
import io.mesazon.gateway.clients.OrganizationS3Client
import io.mesazon.gateway.config.OrganizationS3ClientConfig
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.client4.quick.*
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class OrganizationS3ClientSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  override def beforeEach(): Unit = {
    super.beforeEach()
    val testContext = new TestContext {}

    import testContext.*

    s3TestClient.emptyAllBuckets().zioValue
  }

  "OrganizationS3Client" when {
    "uploadLogo" should {
      "successfully upload the logo and return the key" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val organizationLogoFileName = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes    = ZStream.fromResource("test-logo-1.jpeg")

        val organizationLogoBucketKey =
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
            organizationLogoBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs organizationLogoBytes.runCollect.zioValue
      }

      "successfully upload the same logo and return the key" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val organizationLogoFileName = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes    = ZStream.fromResource("test-logo-1.jpeg")

        val organizationLogoBucketKey1 =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val organizationLogoBucketKey2 =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        organizationLogoBucketKey1 shouldEqual organizationLogoBucketKey2

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            organizationLogoBucketKey2.value,
          )
          .zioValue should contain theSameElementsInOrderAs organizationLogoBytes.runCollect.zioValue
      }

      "successfully upload the different logo and but with the same name" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val organizationLogoFileName = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes1   = ZStream.fromResource("test-logo-1.jpeg")
        val organizationLogoBytes2   = ZStream.fromResource("test-logo-2.webp")

        val organizationLogoBucketKey1 =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes1)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val organizationLogoBucketKey2 =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes2)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        organizationLogoBucketKey1 shouldEqual organizationLogoBucketKey2

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            organizationLogoBucketKey2.value,
          )
          .zioValue should contain theSameElementsInOrderAs organizationLogoBytes2.runCollect.zioValue
      }
    }

    "deleteLogo" should {
      "successfully delete the logo from S3" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val organizationLogoFileName = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes    = ZStream.fromResource("test-logo-1.jpeg")

        val organizationLogoBucketKey =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val _ = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.deleteLogo(organizationLogoBucketKey)
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            organizationLogoBucketKey.value,
          )
          .zioEither
          .isLeft shouldBe true
      }
    }

    "getLogoUrl" should {
      "successfully return a presigned URL for the logo" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val organizationLogoFileName = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes    = ZStream.fromResource("test-logo-1.jpeg")

        val organizationLogoBucketKey =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val presignedUrl =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.getLogoUrl(organizationLogoBucketKey)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val organizationLogoBytesFromPresignedUrl =
          quickRequest
            .get(uri"${presignedUrl.value}")
            .response(asByteArray)
            .send()
            .body
            .getOrElse(Array.emptyByteArray)

        Chunk.from(
          organizationLogoBytesFromPresignedUrl
        ) should contain theSameElementsInOrderAs organizationLogoBytes.runCollect.zioValue
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
      organizationLogoPathPrefix = "organization/logos",
      organizationLogoUrlExpiresAtOffset = 1.minute,
    )

    val s3TestClient = ZIO
      .service[S3TestClient]
      .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      .zioValue
  }
}
