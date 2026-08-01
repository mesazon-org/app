package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.domain.gateway.{CatalogueItemID, OrganizationID}
import io.mesazon.gateway.clients.CatalogueItemImagesS3Client
import io.mesazon.gateway.config.CatalogueItemImagesS3ClientConfig
import io.mesazon.gateway.utils.{ImageNormalizedByteStream, ImageOriginalByteStream}
import io.mesazon.test.s3.S3TestClient
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import sttp.client4.quick.*
import sttp.model.Uri
import zio.*
import zio.stream.ZStream

class CatalogueItemImagesS3ClientSpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/s3.yaml"

  override def exposedServices: Set[ExposedService] = S3TestClient.ExposedServices

  override def beforeEach(): Unit = {
    super.beforeEach()
    val testContext = new TestContext {}

    import testContext.*

    s3TestClient.emptyAllBuckets().zioValue
  }

  "CatalogueItemImagesS3Client" when {
    "upload" should {
      "upload the original and normalized images and return their bucket keys" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemID           = arbitrarySample[CatalogueItemID]
        val imageOriginalByteStream   = ZStream.fromResource("assets/test-logo-1.jpeg")
        val imageNormalizedByteStream = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedImageBucketKeys = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.upload(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream),
              ImageNormalizedByteStream(imageNormalizedByteStream),
            )
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
          )
          .zioValue

        val expectedBucketKeyPrefix =
          s"${catalogueItemImagesS3ClientConfig.bucketPathPrefix}/${organizationID.value}/${catalogueItemID.value}"

        uploadedImageBucketKeys.catalogueItemImageOriginalBucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${catalogueItemImagesS3ClientConfig.originalFileName}"

        uploadedImageBucketKeys.catalogueItemImageNormalizedBucketKey.value shouldBe
          s"$expectedBucketKeyPrefix/${catalogueItemImagesS3ClientConfig.normalizedFileName}"

        s3TestClient
          .getObject(
            catalogueItemImagesS3ClientConfig.bucket,
            uploadedImageBucketKeys.catalogueItemImageOriginalBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageOriginalByteStream.runCollect.zioValue

        s3TestClient
          .getObject(
            catalogueItemImagesS3ClientConfig.bucket,
            uploadedImageBucketKeys.catalogueItemImageNormalizedBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageNormalizedByteStream.runCollect.zioValue
      }

      "overwrite the existing images when uploading again for the same catalogue item" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemID           = arbitrarySample[CatalogueItemID]
        val imageOriginalByteStream1  = ZStream.fromResource("assets/test-logo-1.jpeg")
        val imageOriginalByteStream2  = ZStream.fromResource("assets/test-logo-2.webp")
        val imageOriginalByteStream3  = ZStream.fromResource("assets/test-logo-3.png")

        val uploadedImageBucketKeys1 = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.upload(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream1),
              ImageNormalizedByteStream(imageOriginalByteStream2),
            )
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
          )
          .zioValue

        val uploadedImageBucketKeys2 = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.upload(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream2),
              ImageNormalizedByteStream(imageOriginalByteStream3),
            )
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
          )
          .zioValue

        uploadedImageBucketKeys1 shouldEqual uploadedImageBucketKeys2

        s3TestClient
          .getObject(
            catalogueItemImagesS3ClientConfig.bucket,
            uploadedImageBucketKeys2.catalogueItemImageOriginalBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageOriginalByteStream2.runCollect.zioValue

        s3TestClient
          .getObject(
            catalogueItemImagesS3ClientConfig.bucket,
            uploadedImageBucketKeys2.catalogueItemImageNormalizedBucketKey.value,
          )
          .zioValue should contain theSameElementsInOrderAs imageOriginalByteStream3.runCollect.zioValue
      }
    }

    "getOriginalUrl" should {
      "return a presigned URL that serves the original image" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemID         = arbitrarySample[CatalogueItemID]
        val imageOriginalByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val uploadedImageBucketKeys = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.upload(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(imageOriginalByteStream),
              ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp")),
            )
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
          )
          .zioValue

        val imageOriginalPresignedUrl = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.getOriginalUrl(uploadedImageBucketKeys.catalogueItemImageOriginalBucketKey)
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
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
    }

    "getNormalizedUrl" should {
      "return a presigned URL that serves the normalized image" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val catalogueItemID            = arbitrarySample[CatalogueItemID]
        val imageNormalizedByteStream  = ZStream.fromResource("assets/test-logo-2.webp")

        val uploadedImageBucketKeys = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.upload(
              organizationID,
              catalogueItemID,
              ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg")),
              ImageNormalizedByteStream(imageNormalizedByteStream),
            )
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
          )
          .zioValue

        val imageNormalizedPresignedUrl = ZIO
          .serviceWithZIO[CatalogueItemImagesS3Client](
            _.getNormalizedUrl(uploadedImageBucketKeys.catalogueItemImageNormalizedBucketKey)
          )
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
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
          .serviceWithZIO[CatalogueItemImagesS3Client](_.readiness)
          .provide(
            CatalogueItemImagesS3Client.live,
            ZLayer.succeed(catalogueItemImagesS3ClientConfig),
          )
          .zioEither

        assert(readinessResult.isRight)
      }
    }
  }

  trait TestContext {
    private val s3TestClientConfig = withContainers(S3TestClientConfig.from(_))

    val catalogueItemImagesS3ClientConfig = CatalogueItemImagesS3ClientConfig(
      useMock = true,
      uri = Uri.apply(s3TestClientConfig.uri),
      region = s3TestClientConfig.region,
      accessKeyId = "access-key-id",
      secretAccessKey = "secret-access-key",
      bucket = "catalogue-item-images-bucket",
      bucketPathPrefix = "catalogue/item-images",
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
