package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.OrganizationID
import io.mesazon.gateway.clients.OrganizationS3Client
import io.mesazon.gateway.config.OrganizationS3ClientConfig
import io.mesazon.gateway.utils.{ImageNormalizedByteStream, ImageOriginalByteStream}
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.client4.quick.*
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class OrganizationS3ClientSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  override def beforeEach(): Unit = {
    super.beforeEach()
    val testContext = new TestContext {}

    import testContext.*

    s3TestClient.emptyAllBuckets().zioValue
  }

  "OrganizationS3Client" when {
    "uploadLogos" should {
      "upload the original and normalized logos and return their bucket keys" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val logoOriginalByteStream   = ZStream.fromResource("assets/test-logo-1.jpeg")
        val logoNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedLogoBucketKeys = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.uploadLogos(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream),
              ImageNormalizedByteStream(logoNormalizedByteStream),
            )
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val expectedBucketKeyPrefix =
          s"${organizationS3ClientConfig.organizationLogoBucketPathPrefix}/${organizationID.value}"

        uploadedLogoBucketKeys.organizationLogoOriginalBucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${organizationS3ClientConfig.organizationLogoOriginalFileName}"

        uploadedLogoBucketKeys.organizationLogoNormalizedBucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${organizationS3ClientConfig.organizationLogoNormalizedFileName}"

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            uploadedLogoBucketKeys.organizationLogoOriginalBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoOriginalByteStream.runCollect.zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            uploadedLogoBucketKeys.organizationLogoNormalizedBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoNormalizedByteStream.runCollect.zioValue
      }

      "overwrite the existing logos when uploading again with the same names" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val logoOriginalByteStream1 = ZStream.fromResource("assets/test-logo-1.jpeg")
        val logoOriginalByteStream2 = ZStream.fromResource("assets/test-logo-2.webp")
        val logoOriginalByteStream3 = ZStream.fromResource("assets/test-logo-3.png")

        val uploadedLogoBucketKeys1 = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.uploadLogos(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream1),
              ImageNormalizedByteStream(logoOriginalByteStream2),
            )
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val uploadedLogoBucketKeys2 = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.uploadLogos(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream2),
              ImageNormalizedByteStream(logoOriginalByteStream3),
            )
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        uploadedLogoBucketKeys1 shouldEqual uploadedLogoBucketKeys2

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            uploadedLogoBucketKeys2.organizationLogoOriginalBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoOriginalByteStream2.runCollect.zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            uploadedLogoBucketKeys2.organizationLogoNormalizedBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoOriginalByteStream3.runCollect.zioValue
      }
    }

    "getLogoOriginalUrl" should {
      "return a presigned URL that serves the original logo" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val logoOriginalByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val uploadedLogoBucketKeys = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.uploadLogos(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream),
              ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp")),
            )
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val logoOriginalPresignedUrl = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.getLogoOriginalUrl(uploadedLogoBucketKeys.organizationLogoOriginalBucketKey)
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val logoBytesFromPresignedUrl = quickRequest
          .get(uri"${logoOriginalPresignedUrl.value}")
          .response(asByteArray)
          .send()
          .body
          .getOrElse(Array.emptyByteArray)

        Chunk.from(logoBytesFromPresignedUrl) should contain theSameElementsInOrderAs
          logoOriginalByteStream.runCollect.zioValue
      }
    }

    "getLogoNormalizedUrl" should {
      "return a presigned URL that serves the normalized logo" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val logoNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedLogoBucketKeys = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.uploadLogos(
              organizationID,
              ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg")),
              ImageNormalizedByteStream(logoNormalizedByteStream),
            )
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val logoNormalizedPresignedUrl = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.getLogoNormalizedUrl(uploadedLogoBucketKeys.organizationLogoNormalizedBucketKey)
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val logoBytesFromPresignedUrl = quickRequest
          .get(uri"${logoNormalizedPresignedUrl.value}")
          .response(asByteArray)
          .send()
          .body
          .getOrElse(Array.emptyByteArray)

        Chunk.from(logoBytesFromPresignedUrl) should contain theSameElementsInOrderAs
          logoNormalizedByteStream.runCollect.zioValue
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
      organizationLogoBucketPathPrefix = "organization/logos",
      organizationLogoOriginalFileName = "original",
      organizationLogoNormalizedFileName = "normalized",
      organizationLogoUrlExpiresAtOffset = 1.minute,
    )

    val s3TestClient = ZIO
      .service[S3TestClient]
      .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      .zioValue
  }
}
