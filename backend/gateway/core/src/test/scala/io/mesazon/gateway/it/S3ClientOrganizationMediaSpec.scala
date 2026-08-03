package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.{CatalogueItemID, OrganizationID, S3BucketKey}
import io.mesazon.domain.to
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.config.S3ClientOrganizationMediaConfig
import io.mesazon.gateway.utils.{ImageNormalizedByteStream, ImageOriginalByteStream}
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.client4.quick.*
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class S3ClientOrganizationMediaSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  override def beforeEach(): Unit = {
    super.beforeEach()
    val testContext = new TestContext {}

    import testContext.*

    s3TestClient.emptyAllBuckets().zioValue
  }

  "S3ClientOrganizationMedia" when {
    "upload" should {
      "upload the original and normalized images and return their bucket keys" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemID           = arbitrarySample[CatalogueItemID]
        val imageOriginalByteStream   = ZStream.fromResource("assets/test-logo-1.jpeg")
        val imageNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedImageBucketKeys = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.uploadImage(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream),
              ImageNormalizedByteStream(imageNormalizedByteStream),
            )
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        val expectedBucketKeyPrefix =
          s"${s3ClientOrganizationMediaConfig.catalogueItemImageBucketPathPrefix}/${organizationID.value}/${catalogueItemID.value}"

        uploadedImageBucketKeys.imageOriginalS3BucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${s3ClientOrganizationMediaConfig.originalFileName}"

        uploadedImageBucketKeys.imageNormalizedS3BucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${s3ClientOrganizationMediaConfig.normalizedFileName}"

        s3TestClient
          .getObject(
            s3ClientOrganizationMediaConfig.bucket,
            uploadedImageBucketKeys.imageOriginalS3BucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageOriginalByteStream.runCollect.zioValue

        s3TestClient
          .getObject(
            s3ClientOrganizationMediaConfig.bucket,
            uploadedImageBucketKeys.imageNormalizedS3BucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageNormalizedByteStream.runCollect.zioValue
      }

      "overwrite the existing images when uploading again for the same catalogue item" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemID          = arbitrarySample[CatalogueItemID]
        val imageOriginalByteStream1 = ZStream.fromResource("assets/test-logo-1.jpeg")
        val imageOriginalByteStream2 = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedImageBucketKeys1 = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.uploadImage(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream1),
              ImageNormalizedByteStream(imageOriginalByteStream2),
            )
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        val uploadedImageBucketKeys2 = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.uploadImage(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream2),
              ImageNormalizedByteStream(imageOriginalByteStream1),
            )
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        uploadedImageBucketKeys1 shouldEqual uploadedImageBucketKeys2

        s3TestClient
          .getObject(
            s3ClientOrganizationMediaConfig.bucket,
            uploadedImageBucketKeys2.imageOriginalS3BucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageOriginalByteStream2.runCollect.zioValue
      }
    }

    "genMediaUrl" should {
      "return a presigned URL that serves the original image" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemID         = arbitrarySample[CatalogueItemID]
        val imageOriginalByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val uploadedImageBucketKeys = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.uploadImage(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream),
              ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp")),
            )
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        val imageOriginalPresignedUrl = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.genMediaUrl(uploadedImageBucketKeys.imageOriginalS3BucketKey.to[S3BucketKey])
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        val imageBytesFromPresignedUrl = quickRequest
          .get(uri"$imageOriginalPresignedUrl")
          .response(asByteArray)
          .send()
          .body
          .getOrElse(Array.emptyByteArray)

        Chunk.from(imageBytesFromPresignedUrl) should contain theSameElementsInOrderAs
          imageOriginalByteStream.runCollect.zioValue
      }

      "return a presigned URL that serves the normalized image" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemID           = arbitrarySample[CatalogueItemID]
        val imageNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedImageBucketKeys = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.uploadImage(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg")),
              ImageNormalizedByteStream(imageNormalizedByteStream),
            )
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        val imageNormalizedPresignedUrl = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](
            _.genMediaUrl(uploadedImageBucketKeys.imageNormalizedS3BucketKey.to[S3BucketKey])
          )
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioValue

        val imageBytesFromPresignedUrl = quickRequest
          .get(uri"$imageNormalizedPresignedUrl")
          .response(asByteArray)
          .send()
          .body
          .getOrElse(Array.emptyByteArray)

        Chunk.from(imageBytesFromPresignedUrl) should contain theSameElementsInOrderAs
          imageNormalizedByteStream.runCollect.zioValue
      }
    }

    "readiness" should {
      "return a successful readiness check" in new TestContext {
        val readinessResult = ZIO
          .serviceWithZIO[S3ClientOrganizationMedia](_.readiness)
          .provide(
            S3ClientOrganizationMedia.live,
            ZLayer.succeed(s3ClientOrganizationMediaConfig),
          )
          .zioEither

        assert(readinessResult.isRight)
      }
    }
  }

  trait TestContext {
    private val s3TestClientConfig = withContainers(S3TestClientConfig.from(_))

    val s3ClientOrganizationMediaConfig = S3ClientOrganizationMediaConfig(
      useMock = true,
      uri = Uri.apply(s3TestClientConfig.uri),
      region = s3TestClientConfig.region,
      accessKeyId = "access-key-id",
      secretAccessKey = "secret-access-key",
      bucket = "organization-media",
      catalogueItemImageBucketPathPrefix = "catalogue/item-images",
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
