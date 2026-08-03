package io.mesazon.gateway.clients

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.S3ClientOrganizationMedia.UploadedImageResult
import io.mesazon.gateway.config.S3ClientOrganizationMediaConfig
import io.mesazon.gateway.utils.*
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.*
import software.amazon.awssdk.services.s3.model.{HeadBucketRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import zio.*
import zio.stream.*

import scala.util.chaining.scalaUtilChainingOps

trait S3ClientOrganizationMedia {
  def uploadImage(
      organizationID: OrganizationID,
      catalogueItemID: CatalogueItemID,
      catalogueItemImageOriginalByteStream: ImageOriginalByteStream,
      catalogueItemImageNormalizedByteStream: ImageNormalizedByteStream,
  ): IO[ServiceError, UploadedImageResult]

  def genMediaUrl(
      bucketKey: S3BucketKey
  ): IO[ServiceError, S3MediaUrl]

  def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit]
}

object S3ClientOrganizationMedia {

  type UploadedImageResult = (
      imageOriginalS3BucketKey: ImageOriginalS3BucketKey,
      imageNormalizedS3BucketKey: ImageNormalizedS3BucketKey,
  )

  private final class S3ClientOrganizationMediaImpl(
      s3ClientOrganizationMediaConfig: S3ClientOrganizationMediaConfig,
      s3AsyncClient: S3AsyncClient,
      s3Presigner: S3Presigner,
  ) extends S3ClientOrganizationMedia {

    private def uploadFile(
        bucketPathPrefix: String,
        entityPathSegments: List[String],
        fileName: String,
        fileByteStream: ZStream[Any, Throwable, Byte],
    )(using Trace): IO[ServiceError, String] =
      ZIO.scoped {
        for {
          bucketKey = bucketPathPrefix + "/" + entityPathSegments.mkString("/") + "/" + fileName
          tempPath  <- TempFile.createScoped("s3-client-organization-media-")
          bytesSize <- fileByteStream
            .run(ZSink.fromPath(tempPath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to write organization media to temp file", Some(e))
            )
          putObjectRequest <- ZIO
            .attempt(
              PutObjectRequest
                .builder()
                .bucket(s3ClientOrganizationMediaConfig.bucket)
                .key(bucketKey)
                .contentLength(bytesSize)
                .build()
            )
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError("Failed to create PutObjectRequest", Some(e))
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
              ServiceError.InternalServerError.UnexpectedError("Failed to upload organization media to S3", Some(e))
            )
        } yield bucketKey
      }

    override def uploadImage(
        organizationID: OrganizationID,
        catalogueItemID: CatalogueItemID,
        catalogueItemImageOriginalByteStream: ImageOriginalByteStream,
        catalogueItemImageNormalizedByteStream: ImageNormalizedByteStream,
    ): IO[ServiceError, UploadedImageResult] =
      for {
        imageOriginalS3BucketKeyRaw <- uploadFile(
          s3ClientOrganizationMediaConfig.catalogueItemImageBucketPathPrefix,
          List(organizationID.value.toString, catalogueItemID.value.toString),
          s3ClientOrganizationMediaConfig.originalFileName,
          catalogueItemImageOriginalByteStream.value,
        )
        imageNormalizedS3BucketKeyRaw <- uploadFile(
          s3ClientOrganizationMediaConfig.catalogueItemImageBucketPathPrefix,
          List(organizationID.value.toString, catalogueItemID.value.toString),
          s3ClientOrganizationMediaConfig.normalizedFileName,
          catalogueItemImageNormalizedByteStream.value,
        )
        imageOriginalS3BucketKey <- ZIO
          .fromEither(ImageOriginalS3BucketKey.either(imageOriginalS3BucketKeyRaw))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(s"Failed to construct ImageOriginalS3BucketKey: [$e]")
          )
        imageNormalizedS3BucketKey <- ZIO
          .fromEither(ImageNormalizedS3BucketKey.either(imageNormalizedS3BucketKeyRaw))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(s"Failed to construct ImageNormalizedS3BucketKey: [$e]")
          )
      } yield (imageOriginalS3BucketKey, imageNormalizedS3BucketKey)

    override def genMediaUrl(
        bucketKey: S3BucketKey
    ): IO[ServiceError, S3MediaUrl] =
      for {
        getObjectRequest <- ZIO
          .attempt(
            software.amazon.awssdk.services.s3.model.GetObjectRequest
              .builder()
              .bucket(s3ClientOrganizationMediaConfig.bucket)
              .key(bucketKey.value)
              .build()
          )
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectRequest", Some(e)))
        presignGetObjectRequest <- ZIO
          .attempt(
            software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
              .builder()
              .getObjectRequest(getObjectRequest)
              .signatureDuration(s3ClientOrganizationMediaConfig.urlExpiresAtOffset)
              .build()
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectPresignRequest", Some(e))
          )
        mediaUrlRaw <- ZIO
          .attempt(s3Presigner.presignGetObject(presignGetObjectRequest).url().toString)
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to presignGetObject request", Some(e))
          )
        mediaUrl <- ZIO
          .fromEither(S3MediaUrl.either(mediaUrlRaw))
          .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct S3MediaUrl: [$e]"))
      } yield mediaUrl

    override def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit] =
      ZIO
        .attempt(
          HeadBucketRequest
            .builder()
            .bucket(s3ClientOrganizationMediaConfig.bucket)
            .build()
        )
        .flatMap(headBucketRequest => ZIO.fromCompletableFuture(s3AsyncClient.headBucket(headBucketRequest)))
        .mapError(ServiceError.ServiceUnavailableError.S3UnavailableError(s3ClientOrganizationMediaConfig.bucket, _))
        .unit
  }

  private val s3AsyncClientLayer: ZLayer[S3ClientOrganizationMediaConfig, Throwable, S3AsyncClient] =
    ZLayer.scoped(
      for {
        s3ClientOrganizationMediaConfig <- ZIO.service[S3ClientOrganizationMediaConfig]
        s3AsyncClient                   <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            S3AsyncClient
              .builder()
              .region(s3ClientOrganizationMediaConfig.region)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials
                    .create(
                      s3ClientOrganizationMediaConfig.accessKeyId,
                      s3ClientOrganizationMediaConfig.secretAccessKey,
                    )
                )
              )
              .endpointOverride(s3ClientOrganizationMediaConfig.uri.toJavaUri)
              .pipe { builder =>
                if (s3ClientOrganizationMediaConfig.useMock)
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

  private val s3PresignerLayer: ZLayer[S3ClientOrganizationMediaConfig, Throwable, S3Presigner] =
    ZLayer.scoped(
      for {
        s3ClientOrganizationMediaConfig <- ZIO.service[S3ClientOrganizationMediaConfig]
        s3Presigner                     <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            S3Presigner
              .builder()
              .region(s3ClientOrganizationMediaConfig.region)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials
                    .create(
                      s3ClientOrganizationMediaConfig.accessKeyId,
                      s3ClientOrganizationMediaConfig.secretAccessKey,
                    )
                )
              )
              .pipe { builder =>
                if (s3ClientOrganizationMediaConfig.useMock)
                  builder
                    .endpointOverride(s3ClientOrganizationMediaConfig.uri.toJavaUri)
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

  private def observed(s3ClientOrganizationMedia: S3ClientOrganizationMedia): S3ClientOrganizationMedia =
    new S3ClientOrganizationMedia {
      override def uploadImage(
          organizationID: OrganizationID,
          catalogueItemID: CatalogueItemID,
          catalogueItemImageOriginalByteStream: ImageOriginalByteStream,
          catalogueItemImageNormalizedByteStream: ImageNormalizedByteStream,
      ): IO[ServiceError, UploadedImageResult] =
        s3ClientOrganizationMedia.uploadImage(
          organizationID,
          catalogueItemID,
          catalogueItemImageOriginalByteStream,
          catalogueItemImageNormalizedByteStream,
        )

      override def genMediaUrl(
          bucketKey: S3BucketKey
      ): IO[ServiceError, S3MediaUrl] =
        s3ClientOrganizationMedia.genMediaUrl(bucketKey)

      override def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit] =
        s3ClientOrganizationMedia.readiness
    }

  val live =
    (s3PresignerLayer ++ s3AsyncClientLayer) >>> ZLayer.derive[S3ClientOrganizationMediaImpl] >>>
      ZLayer.fromFunction(observed)
}
