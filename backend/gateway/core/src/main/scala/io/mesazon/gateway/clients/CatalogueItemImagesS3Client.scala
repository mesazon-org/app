package io.mesazon.gateway.clients

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.CatalogueItemImagesS3Client.UploadedImageResult
import io.mesazon.gateway.config.CatalogueItemImagesS3ClientConfig
import io.mesazon.gateway.utils.*
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.*
import software.amazon.awssdk.services.s3.model.{HeadBucketRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import zio.*
import zio.stream.*

import scala.util.chaining.scalaUtilChainingOps

trait CatalogueItemImagesS3Client {
  def upload(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemImageOriginalByteStream: ImageOriginalByteStream,
      catalogueItemImageNormalizedByteStream: ImageNormalizedByteStream,
  ): IO[ServiceError, UploadedImageResult]

  def getOriginalUrl(
      catalogueItemImageOriginalBucketKey: PhotoOriginalBucketKey
  ): IO[ServiceError, String]

  def getNormalizedUrl(
      catalogueItemImageNormalizedBucketKey: PhotoNormalizedBucketKey
  ): IO[ServiceError, String]

  def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit]
}

object CatalogueItemImagesS3Client {

  type UploadedImageResult = (
      catalogueItemImageOriginalBucketKey: PhotoOriginalBucketKey,
      catalogueItemImageNormalizedBucketKey: PhotoNormalizedBucketKey,
  )

  private final class CatalogueItemImagesS3ClientImpl(
      catalogueItemImagesS3ClientConfig: CatalogueItemImagesS3ClientConfig,
      s3AsyncClient: S3AsyncClient,
      s3Presigner: S3Presigner,
  ) extends CatalogueItemImagesS3Client {

    private def uploadFile(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueBucket: String,
        catalogueBucketPathPrefix: String,
        catalogueFileName: String,
        catalogueFileByteStream: ZStream[Any, Throwable, Byte],
    )(using Trace): IO[ServiceError, String] =
      ZIO.scoped {
        for {
          bucketKey = s"$catalogueBucketPathPrefix/${organizationID.value}/${catalogueItemID.value}/$catalogueFileName"
          tempPath  <- TempFile.createScoped("catalogue-item-image-s3-upload-")
          bytesSize <- catalogueFileByteStream
            .run(ZSink.fromPath(tempPath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to write catalogue item image to temp file", Some(e))
            )
          putObjectRequest <- ZIO
            .attempt(
              PutObjectRequest
                .builder()
                .bucket(catalogueBucket)
                .key(bucketKey)
                .contentLength(bytesSize)
                .build()
            )
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to create PutObjectRequest", Some(e))
            )
          asyncRequestBody <- ZIO
            .attempt(AsyncRequestBody.fromFile(tempPath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to create AsyncRequestBody from temp file", Some(e))
            )
          _ <- ZIO
            .fromCompletableFuture(
              s3AsyncClient.putObject(putObjectRequest, asyncRequestBody)
            )
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError("Failed to upload catalogue item image to S3", Some(e))
            )
        } yield bucketKey
      }

    private def getImageUrl(
        catalogueBucket: String,
        catalogueImageBucketKey: String,
    )(using Trace): IO[ServiceError, String] =
      for {
        getObjectRequest <- ZIO
          .attempt(
            software.amazon.awssdk.services.s3.model.GetObjectRequest
              .builder()
              .bucket(catalogueBucket)
              .key(catalogueImageBucketKey)
              .build()
          )
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectRequest", Some(e)))
        presignGetObjectRequest <- ZIO
          .attempt(
            software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
              .builder()
              .getObjectRequest(getObjectRequest)
              .signatureDuration(catalogueItemImagesS3ClientConfig.urlExpiresAtOffset)
              .build()
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectPresignRequest", Some(e))
          )
        url <- ZIO
          .attempt(s3Presigner.presignGetObject(presignGetObjectRequest).url())
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to presignGetObject request", Some(e))
          )
      } yield url.toString

    override def upload(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemImageOriginalByteStream: ImageOriginalByteStream,
        catalogueItemImageNormalizedByteStream: ImageNormalizedByteStream,
    ): IO[ServiceError, UploadedImageResult] =
      for {
        catalogueItemImageOriginalBucketKeyRaw <- uploadFile(
          organizationID,
          catalogueItemID,
          catalogueItemImagesS3ClientConfig.bucket,
          catalogueItemImagesS3ClientConfig.bucketPathPrefix,
          catalogueItemImagesS3ClientConfig.originalFileName,
          catalogueItemImageOriginalByteStream.value,
        )
        catalogueItemImageNormalizedBucketKeyRaw <- uploadFile(
          organizationID,
          catalogueItemID,
          catalogueItemImagesS3ClientConfig.bucket,
          catalogueItemImagesS3ClientConfig.bucketPathPrefix,
          catalogueItemImagesS3ClientConfig.normalizedFileName,
          catalogueItemImageNormalizedByteStream.value,
        )
        catalogueItemImageOriginalBucketKey <- ZIO
          .fromEither(PhotoOriginalBucketKey.either(catalogueItemImageOriginalBucketKeyRaw))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to construct PhotoOriginalBucketKey: [$e]"
            )
          )
        catalogueItemImageNormalizedBucketKey <- ZIO
          .fromEither(PhotoNormalizedBucketKey.either(catalogueItemImageNormalizedBucketKeyRaw))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to construct PhotoNormalizedBucketKey: [$e]"
            )
          )
      } yield (catalogueItemImageOriginalBucketKey, catalogueItemImageNormalizedBucketKey)

    override def getOriginalUrl(
        catalogueItemImageOriginalBucketKey: PhotoOriginalBucketKey
    ): IO[ServiceError, String] =
      getImageUrl(
        catalogueItemImagesS3ClientConfig.bucket,
        catalogueItemImageOriginalBucketKey.value,
      )

    override def getNormalizedUrl(
        catalogueItemImageNormalizedBucketKey: PhotoNormalizedBucketKey
    ): IO[ServiceError, String] =
      getImageUrl(
        catalogueItemImagesS3ClientConfig.bucket,
        catalogueItemImageNormalizedBucketKey.value,
      )

    override def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit] =
      ZIO
        .attempt(
          HeadBucketRequest
            .builder()
            .bucket(catalogueItemImagesS3ClientConfig.bucket)
            .build()
        )
        .flatMap(headBucketRequest => ZIO.fromCompletableFuture(s3AsyncClient.headBucket(headBucketRequest)))
        .mapError(ServiceError.ServiceUnavailableError.S3UnavailableError(catalogueItemImagesS3ClientConfig.bucket, _))
        .unit
  }

  private val s3AsyncClientLayer: ZLayer[CatalogueItemImagesS3ClientConfig, Throwable, S3AsyncClient] =
    ZLayer.scoped(
      for {
        catalogueItemImagesS3ClientConfig <- ZIO.service[CatalogueItemImagesS3ClientConfig]
        s3AsyncClient                     <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            S3AsyncClient
              .builder()
              .region(catalogueItemImagesS3ClientConfig.region)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials
                    .create(catalogueItemImagesS3ClientConfig.accessKeyId, catalogueItemImagesS3ClientConfig.secretAccessKey)
                )
              )
              .endpointOverride(catalogueItemImagesS3ClientConfig.uri.toJavaUri)
              .pipe { builder =>
                if (catalogueItemImagesS3ClientConfig.useMock)
                  builder
                    .serviceConfiguration(
                      S3Configuration.builder().pathStyleAccessEnabled(true).build()
                    )
                else
                  builder
              }
              .build()
          )
        )(client =>
          ZIO.succeed(client.close()) <* ZIO.logError(
            "S3AsyncClient closed"
          )
        )
      } yield s3AsyncClient
    )

  private val s3PresignerLayer: ZLayer[CatalogueItemImagesS3ClientConfig, Throwable, S3Presigner] =
    ZLayer.scoped(
      for {
        catalogueItemImagesS3ClientConfig <- ZIO.service[CatalogueItemImagesS3ClientConfig]
        s3Presigner                       <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            S3Presigner
              .builder()
              .region(catalogueItemImagesS3ClientConfig.region)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials
                    .create(catalogueItemImagesS3ClientConfig.accessKeyId, catalogueItemImagesS3ClientConfig.secretAccessKey)
                )
              )
              .pipe { builder =>
                if (catalogueItemImagesS3ClientConfig.useMock)
                  builder
                    .endpointOverride(catalogueItemImagesS3ClientConfig.uri.toJavaUri)
                    .serviceConfiguration(
                      S3Configuration.builder().pathStyleAccessEnabled(true).build()
                    )
                else
                  builder
              }
              .build()
          )
        )(s3Presigner => ZIO.succeed(s3Presigner.close()) <* ZIO.logError("S3Presigner closed"))
      } yield s3Presigner
    )

  private def observed(catalogueItemImagesS3Client: CatalogueItemImagesS3Client): CatalogueItemImagesS3Client =
    new CatalogueItemImagesS3Client {
      override def upload(
          organizationID: OrganizationID,
          catalogueItemID: CatalogueItemID,
          catalogueItemImageOriginalByteStream: ImageOriginalByteStream,
          catalogueItemImageNormalizedByteStream: ImageNormalizedByteStream,
      ): IO[ServiceError, UploadedImageResult] =
        catalogueItemImagesS3Client
          .upload(organizationID, catalogueItemID, catalogueItemImageOriginalByteStream, catalogueItemImageNormalizedByteStream)

      override def getOriginalUrl(
          catalogueItemImageOriginalBucketKey: PhotoOriginalBucketKey
      ): IO[ServiceError, String] =
        catalogueItemImagesS3Client.getOriginalUrl(catalogueItemImageOriginalBucketKey)

      override def getNormalizedUrl(
          catalogueItemImageNormalizedBucketKey: PhotoNormalizedBucketKey
      ): IO[ServiceError, String] =
        catalogueItemImagesS3Client.getNormalizedUrl(catalogueItemImageNormalizedBucketKey)

      override def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit] =
        catalogueItemImagesS3Client.readiness
    }

  val live =
    (s3PresignerLayer ++ s3AsyncClientLayer) >>> ZLayer.derive[CatalogueItemImagesS3ClientImpl] >>>
      ZLayer.fromFunction(observed)
}
