package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.{OrganizationID, OrganizationLogoFileName}
import io.mesazon.gateway.clients.OrganizationS3Client
import io.mesazon.gateway.config.OrganizationS3ClientConfig
import io.mesazon.gateway.utils.ImageProcessing
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.client4.quick.*
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class OrganizationS3ClientSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/docker-compose/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  override def beforeEach(): Unit = {
    super.beforeEach()
    val testContext = new TestContext {}

    import testContext.*

    s3TestClient.emptyAllBuckets().zioValue
  }

  "OrganizationS3Client" when {
    "uploadLogo" should {
      "successfully upload all three renditions and return their keys" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val originalLogo   = ZStream.fromResource("assets/test-logo-1.jpeg")
        val normalizedLogo = ZStream.fromResource("assets/test-logo-2.webp")
        val whatsAppLogo   = ZStream.fromResource("assets/test-logo-3.png")

        val processedLogo = ImageProcessing.ProcessedLogo(
          originalLogo = originalLogo,
          originalLogoFileName = OrganizationLogoFileName.assume("original.jpeg"),
          normalizedLogo = normalizedLogo,
          normalizedLogoFileName = OrganizationLogoFileName.assume("normalized.webp"),
          whatsAppLogo = whatsAppLogo,
          whatsAppLogoFileName = OrganizationLogoFileName.assume("whatsapp.jpeg"),
        )

        val uploadedLogoKeys =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogos(organizationID, processedLogo)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val expectedPrefix =
          s"${organizationS3ClientConfig.organizationLogoPathPrefix}/${organizationID.value}"
        uploadedLogoKeys.originalLogoBucketKey.value shouldBe s"$expectedPrefix/original.jpeg"
        uploadedLogoKeys.normalizedLogoBucketKey.value shouldBe s"$expectedPrefix/normalized.webp"
        uploadedLogoKeys.whatsAppLogoBucketKey.value shouldBe s"$expectedPrefix/whatsapp.jpeg"

        s3TestClient
          .getObject(organizationS3ClientConfig.organizationLogoBucket, uploadedLogoKeys.originalLogoBucketKey.value)
          .zioValue should contain theSameElementsInOrderAs originalLogo.runCollect.zioValue
        s3TestClient
          .getObject(organizationS3ClientConfig.organizationLogoBucket, uploadedLogoKeys.normalizedLogoBucketKey.value)
          .zioValue should contain theSameElementsInOrderAs normalizedLogo.runCollect.zioValue
        s3TestClient
          .getObject(organizationS3ClientConfig.organizationLogoBucket, uploadedLogoKeys.whatsAppLogoBucketKey.value)
          .zioValue should contain theSameElementsInOrderAs whatsAppLogo.runCollect.zioValue
      }

      "successfully overwrite previously uploaded renditions when uploading again with the same names" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val originalLogo1  = ZStream.fromResource("assets/test-logo-1.jpeg")
        val originalLogo2  = ZStream.fromResource("assets/test-logo-2.webp")

        def processedLogo(originalLogo: ZStream[Any, Throwable, Byte]) =
          ImageProcessing.ProcessedLogo(
            originalLogo = originalLogo,
            originalLogoFileName = OrganizationLogoFileName.assume("original.jpeg"),
            normalizedLogo = ZStream.fromResource("assets/test-logo-2.webp"),
            normalizedLogoFileName = OrganizationLogoFileName.assume("normalized.webp"),
            whatsAppLogo = ZStream.fromResource("assets/test-logo-3.png"),
            whatsAppLogoFileName = OrganizationLogoFileName.assume("whatsapp.jpeg"),
          )

        def upload(originalLogo: ZStream[Any, Throwable, Byte]) =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogos(organizationID, processedLogo(originalLogo))
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val uploadedLogoKeys1 = upload(originalLogo1)
        val uploadedLogoKeys2 = upload(originalLogo2)

        uploadedLogoKeys1 shouldEqual uploadedLogoKeys2

        s3TestClient
          .getObject(organizationS3ClientConfig.organizationLogoBucket, uploadedLogoKeys2.originalLogoBucketKey.value)
          .zioValue should contain theSameElementsInOrderAs originalLogo2.runCollect.zioValue
      }
    }

    "deleteLogo" should {
      "successfully delete the logo from S3" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        val processedLogo = ImageProcessing.ProcessedLogo(
          originalLogo = ZStream.fromResource("assets/test-logo-1.jpeg"),
          originalLogoFileName = OrganizationLogoFileName.assume("original.jpeg"),
          normalizedLogo = ZStream.fromResource("assets/test-logo-2.webp"),
          normalizedLogoFileName = OrganizationLogoFileName.assume("normalized.webp"),
          whatsAppLogo = ZStream.fromResource("assets/test-logo-3.png"),
          whatsAppLogoFileName = OrganizationLogoFileName.assume("whatsapp.jpeg"),
        )

        val uploadedLogoKeys =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogos(organizationID, processedLogo)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val _ = ZIO
          .serviceWithZIO[OrganizationS3Client](
            _.deleteLogo(uploadedLogoKeys.originalLogoBucketKey)
          )
          .provide(
            OrganizationS3Client.live,
            ZLayer.succeed(organizationS3ClientConfig),
          )
          .zioValue

        s3TestClient
          .getObject(
            organizationS3ClientConfig.organizationLogoBucket,
            uploadedLogoKeys.originalLogoBucketKey.value,
          )
          .zioEither
          .isLeft shouldBe true
      }
    }

    "getLogoUrl" should {
      "successfully return a presigned URL for the logo" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]
        val originalLogo   = ZStream.fromResource("assets/test-logo-1.jpeg")

        val processedLogo = ImageProcessing.ProcessedLogo(
          originalLogo = originalLogo,
          originalLogoFileName = OrganizationLogoFileName.assume("original.jpeg"),
          normalizedLogo = ZStream.fromResource("assets/test-logo-2.webp"),
          normalizedLogoFileName = OrganizationLogoFileName.assume("normalized.webp"),
          whatsAppLogo = ZStream.fromResource("assets/test-logo-3.png"),
          whatsAppLogoFileName = OrganizationLogoFileName.assume("whatsapp.jpeg"),
        )

        val uploadedLogoKeys =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.uploadLogos(organizationID, processedLogo)
            )
            .provide(
              OrganizationS3Client.live,
              ZLayer.succeed(organizationS3ClientConfig),
            )
            .zioValue

        val presignedUrl =
          ZIO
            .serviceWithZIO[OrganizationS3Client](
              _.getLogoUrl(uploadedLogoKeys.originalLogoBucketKey)
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
        ) should contain theSameElementsInOrderAs originalLogo.runCollect.zioValue
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
