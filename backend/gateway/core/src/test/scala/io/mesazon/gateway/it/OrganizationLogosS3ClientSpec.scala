package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.OrganizationID
import io.mesazon.gateway.clients.OrganizationLogosS3Client
import io.mesazon.gateway.config.OrganizationLogosS3ClientConfig
import io.mesazon.gateway.utils.{ImageNormalizedByteStream, ImageOriginalByteStream}
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.client4.quick.*
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class OrganizationLogosS3ClientSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  override def beforeEach(): Unit = {
    super.beforeEach()
    val testContext = new TestContext {}

    import testContext.*

    s3TestClient.emptyAllBuckets().zioValue
  }

  "OrganizationLogosS3Client" when {
    "upload" should {
      "upload the original and normalized logos and return their bucket keys" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val logoOriginalByteStream   = ZStream.fromResource("assets/test-logo-1.jpeg")
        val logoNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedLogoBucketKeys = ZIO
          .serviceWithZIO[OrganizationLogosS3Client](
            _.upload(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream),
              ImageNormalizedByteStream(logoNormalizedByteStream),
            )
          )
          .provide(
            OrganizationLogosS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val expectedBucketKeyPrefix =
          s"${organizationS3ClientConfig.bucketPathPrefix}/${organizationID.value}"

        uploadedLogoBucketKeys.organizationLogoOriginalBucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${organizationS3ClientConfig.originalFileName}"

        uploadedLogoBucketKeys.organizationLogoNormalizedBucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${organizationS3ClientConfig.normalizedFileName}"

        s3TestClient
          .getObject(
            organizationS3ClientConfig.bucket,
            uploadedLogoBucketKeys.organizationLogoOriginalBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoOriginalByteStream.runCollect.zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.bucket,
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
          .serviceWithZIO[OrganizationLogosS3Client](
            _.upload(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream1),
              ImageNormalizedByteStream(logoOriginalByteStream2),
            )
          )
          .provide(
            OrganizationLogosS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val uploadedLogoBucketKeys2 = ZIO
          .serviceWithZIO[OrganizationLogosS3Client](
            _.upload(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream2),
              ImageNormalizedByteStream(logoOriginalByteStream3),
            )
          )
          .provide(
            OrganizationLogosS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        uploadedLogoBucketKeys1 shouldEqual uploadedLogoBucketKeys2

        s3TestClient
          .getObject(
            organizationS3ClientConfig.bucket,
            uploadedLogoBucketKeys2.organizationLogoOriginalBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoOriginalByteStream2.runCollect.zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.bucket,
            uploadedLogoBucketKeys2.organizationLogoNormalizedBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs logoOriginalByteStream3.runCollect.zioValue
      }
    }

    "getOriginalUrl" should {
      "return a presigned URL that serves the original logo" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val logoOriginalByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val uploadedLogoBucketKeys = ZIO
          .serviceWithZIO[OrganizationLogosS3Client](
            _.upload(
              organizationID,
              ImageOriginalByteStream(logoOriginalByteStream),
              ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp")),
            )
          )
          .provide(
            OrganizationLogosS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val logoOriginalPresignedUrl = ZIO
          .serviceWithZIO[OrganizationLogosS3Client](
            _.getOriginalUrl(uploadedLogoBucketKeys.organizationLogoOriginalBucketKey)
          )
          .provide(
            OrganizationLogosS3Client.live,
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

    "getNormalizedUrl" should {
      "return a presigned URL that serves the normalized logo" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val logoNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedLogoBucketKeys = ZIO
          .serviceWithZIO[OrganizationLogosS3Client](
            _.upload(
              organizationID,
              ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg")),
              ImageNormalizedByteStream(logoNormalizedByteStream),
            )
          )
          .provide(
            OrganizationLogosS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        val logoNormalizedPresignedUrl = ZIO
          .serviceWithZIO[OrganizationLogosS3Client](
            _.getNormalizedUrl(uploadedLogoBucketKeys.organizationLogoNormalizedBucketKey)
          )
          .provide(
            OrganizationLogosS3Client.live,
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

    val organizationS3ClientConfig = OrganizationLogosS3ClientConfig(
      useMock = true,
      uri = Uri.apply(s3TestClientConfig.uri),
      region = s3TestClientConfig.region,
      accessKeyId = "access-key-id",
      secretAccessKey = "secret-access-key",
      bucket = "organization-logo-bucket",
      bucketPathPrefix = "organization/logos",
      originalFileName = "original",
      normalizedFileName = "normalized",
      urlExpiresAtOffset = 1.minute,
    )

    val s3TestClient = ZIO
      .service[S3TestClient]
      .provide(S3TestClient.live, ZLayer.succeed(s3TestClientConfig))
      .zioValue
  }
}
