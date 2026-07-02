package io.mesazon.gateway.clients

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.OrganizationLogosS3Client.UploadedLogoResult
import io.mesazon.gateway.config.OrganizationLogosS3ClientConfig
import io.mesazon.gateway.utils.*
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.*
import software.amazon.awssdk.services.s3.model.{
  HeadBucketRequest,
  PutObjectRequest
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import zio.*
import zio.stream.*

import scala.util.chaining.scalaUtilChainingOps

trait OrganizationLogosS3Client {
  def upload(
      organizationID: OrganizationID,
      organizationLogoOriginalByteStream: ImageOriginalByteStream,
      organizationLogoNormalizedByteStream: ImageNormalizedByteStream,
  ): IO[ServiceError, UploadedLogoResult]

  def getOriginalUrl(
      organizationLogoOriginalBucketKey: OrganizationLogoOriginalBucketKey
  ): IO[ServiceError, OrganizationLogoOriginalUrl]

  def getNormalizedUrl(
      organizationLogoNormalizedBucketKey: OrganizationLogoNormalizedBucketKey
  ): IO[ServiceError, OrganizationLogoNormalizedUrl]

  def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit]
}

object OrganizationLogosS3Client {

  type UploadedLogoResult = (
      organizationLogoOriginalBucketKey: OrganizationLogoOriginalBucketKey,
      organizationLogoNormalizedBucketKey: OrganizationLogoNormalizedBucketKey,
  )

  private final class OrganizationLogosS3ClientImpl(
      organizationLogosS3ClientConfig: OrganizationLogosS3ClientConfig,
      s3AsyncClient: S3AsyncClient,
      s3Presigner: S3Presigner,
  ) extends OrganizationLogosS3Client {

    private def uploadFile(
        organizationID: OrganizationID,
        organizationBucket: String,
        organizationBucketPathPrefix: String,
        organizationFileName: String,
        organizationFileByteStream: ZStream[Any, Throwable, Byte],
    )(using Trace): IO[ServiceError, String] =
      ZIO.scoped {
        for {
          bucketKey = s"$organizationBucketPathPrefix/${organizationID.value}/$organizationFileName"
          tempPath  <- TempFile.createScoped("organization-s3-upload-")
          bytesSize <- organizationFileByteStream
            .run(ZSink.fromPath(tempPath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to write organization logo to temp file", Some(e))
            )
          putObjectRequest <- ZIO
            .attempt(
              PutObjectRequest
                .builder()
                .bucket(organizationBucket)
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
              ServiceError.InternalServerError.UnexpectedError("Failed to upload organization logo to S3", Some(e))
            )
        } yield bucketKey
      }

    private def getLogoUrl(
        organizationBucket: String,
        organizationLogoBucketKey: String,
    )(using Trace): IO[ServiceError, String] =
      for {
        getObjectRequest <- ZIO
          .attempt(
            software.amazon.awssdk.services.s3.model.GetObjectRequest
              .builder()
              .bucket(organizationBucket)
              .key(organizationLogoBucketKey)
              .build()
          )
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectRequest", Some(e)))
        presignGetObjectRequest <- ZIO
          .attempt(
            software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
              .builder()
              .getObjectRequest(getObjectRequest)
              .signatureDuration(organizationLogosS3ClientConfig.urlExpiresAtOffset)
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
        organizationLogoOriginalByteStream: ImageOriginalByteStream,
        organizationLogoNormalizedByteStream: ImageNormalizedByteStream,
    ): IO[ServiceError, UploadedLogoResult] =
      for {
        organizationLogoOriginalBucketKeyRaw <- uploadFile(
          organizationID,
          organizationLogosS3ClientConfig.bucket,
          organizationLogosS3ClientConfig.bucketPathPrefix,
          organizationLogosS3ClientConfig.originalFileName,
          organizationLogoOriginalByteStream.value,
        )
        organizationLogoNormalizedBucketKeyRaw <- uploadFile(
          organizationID,
          organizationLogosS3ClientConfig.bucket,
          organizationLogosS3ClientConfig.bucketPathPrefix,
          organizationLogosS3ClientConfig.normalizedFileName,
          organizationLogoNormalizedByteStream.value,
        )
        organizationLogoOriginalBucketKey <- ZIO
          .fromEither(OrganizationLogoOriginalBucketKey.either(organizationLogoOriginalBucketKeyRaw))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to construct OrganizationLogoOriginalBucketKey: [$e]"
            )
          )
        organizationLogoNormalizedBucketKey <- ZIO
          .fromEither(OrganizationLogoNormalizedBucketKey.either(organizationLogoNormalizedBucketKeyRaw))
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(
              s"Failed to construct OrganizationLogoNormalizedBucketKey: [$e]"
            )
          )
      } yield (organizationLogoOriginalBucketKey, organizationLogoNormalizedBucketKey)

    override def getOriginalUrl(
        organizationLogoOriginalBucketKey: OrganizationLogoOriginalBucketKey
    ): IO[ServiceError, OrganizationLogoOriginalUrl] =
      for {
        organizationLogoOriginalUrlRaw <- getLogoUrl(
          organizationLogosS3ClientConfig.bucket,
          organizationLogoOriginalBucketKey.value,
        )
        organizationLogoOriginalUrl <- ZIO
          .fromEither(
            OrganizationLogoOriginalUrl
              .either(organizationLogoOriginalUrlRaw)
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(s"Failed to construct OrganizationLogoUrl: [$e]")
          )
      } yield organizationLogoOriginalUrl

    override def getNormalizedUrl(
        organizationLogoNormalizedBucketKey: OrganizationLogoNormalizedBucketKey
    ): IO[ServiceError, OrganizationLogoNormalizedUrl] =
      for {
        organizationLogoNormalizedUrlRaw <- getLogoUrl(
          organizationLogosS3ClientConfig.bucket,
          organizationLogoNormalizedBucketKey.value,
        )
        organizationLogoNormalizedUrl <- ZIO
          .fromEither(
            OrganizationLogoNormalizedUrl
              .either(organizationLogoNormalizedUrlRaw)
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(s"Failed to construct OrganizationLogoUrl: [$e]")
          )
      } yield organizationLogoNormalizedUrl

    override def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit] =
      ZIO
        .attempt(
          HeadBucketRequest
            .builder()
            .bucket(organizationLogosS3ClientConfig.bucket)
            .build()
        )
        .flatMap(headBucketRequest => ZIO.fromCompletableFuture(s3AsyncClient.headBucket(headBucketRequest)))
        .mapError(ServiceError.ServiceUnavailableError.S3UnavailableError(organizationLogosS3ClientConfig.bucket, _))
        .unit
  }

  private val s3AsyncClientLayer: ZLayer[OrganizationLogosS3ClientConfig, Throwable, S3AsyncClient] =
    ZLayer.scoped(
      for {
        organizationS3ClientConfig <- ZIO.service[OrganizationLogosS3ClientConfig]
        s3AsyncClient              <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            S3AsyncClient
              .builder()
              .region(organizationS3ClientConfig.region)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials
                    .create(organizationS3ClientConfig.accessKeyId, organizationS3ClientConfig.secretAccessKey)
                )
              )
              .endpointOverride(organizationS3ClientConfig.uri.toJavaUri)
              .pipe { builder =>
                if (organizationS3ClientConfig.useMock)
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

  private val s3PresignerLayer: ZLayer[OrganizationLogosS3ClientConfig, Throwable, S3Presigner] =
    ZLayer.scoped(
      for {
        organizationS3ClientConfig <- ZIO.service[OrganizationLogosS3ClientConfig]
        s3Presigner                <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            S3Presigner
              .builder()
              .region(organizationS3ClientConfig.region)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials
                    .create(organizationS3ClientConfig.accessKeyId, organizationS3ClientConfig.secretAccessKey)
                )
              )
              .pipe { builder =>
                if (organizationS3ClientConfig.useMock)
                  builder
                    .endpointOverride(organizationS3ClientConfig.uri.toJavaUri)
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

  private def observed(organizationLogosS3Client: OrganizationLogosS3Client): OrganizationLogosS3Client =
    new OrganizationLogosS3Client {
      override def upload(
          organizationID: OrganizationID,
          organizationLogoOriginalByteStream: ImageOriginalByteStream,
          organizationLogoNormalizedByteStream: ImageNormalizedByteStream,
      ): IO[ServiceError, UploadedLogoResult] =
        organizationLogosS3Client
          .upload(organizationID, organizationLogoOriginalByteStream, organizationLogoNormalizedByteStream)

      override def getOriginalUrl(
          organizationLogoOriginalBucketKey: OrganizationLogoOriginalBucketKey
      ): IO[ServiceError, OrganizationLogoOriginalUrl] =
        organizationLogosS3Client.getOriginalUrl(organizationLogoOriginalBucketKey)

      override def getNormalizedUrl(
          organizationLogoNormalizedBucketKey: OrganizationLogoNormalizedBucketKey
      ): IO[ServiceError, OrganizationLogoNormalizedUrl] =
        organizationLogosS3Client.getNormalizedUrl(organizationLogoNormalizedBucketKey)

      override def readiness: IO[ServiceError.ServiceUnavailableError.S3UnavailableError, Unit] =
        organizationLogosS3Client.readiness
    }

  val live =
    (s3PresignerLayer ++ s3AsyncClientLayer) >>> ZLayer.derive[OrganizationLogosS3ClientImpl] >>>
      ZLayer.fromFunction(observed)
}
