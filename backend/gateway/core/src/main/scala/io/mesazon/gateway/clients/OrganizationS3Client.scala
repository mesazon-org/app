package io.mesazon.gateway.clients

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.InternalServerError
import io.mesazon.gateway.config.OrganizationS3ClientConfig
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.*
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import zio.*
import zio.stream.*

import java.nio.file.{Files, Path}
import scala.util.chaining.scalaUtilChainingOps

trait OrganizationS3Client {
  def uploadLogo(
      organizationID: OrganizationID,
      organizationLogoFileName: OrganizationLogoFileName,
      organizationLogoBytes: ZStream[Any, Throwable, Byte],
  ): IO[ServiceError, OrganizationLogoBucketKey]

  def getLogoUrl(
      organizationLogoBucketKey: OrganizationLogoBucketKey
  ): IO[ServiceError, OrganizationLogoUrl]
}

object OrganizationS3Client {

  final class OrganizationS3ClientImpl(
      organizationS3ClientConfig: OrganizationS3ClientConfig,
      s3AsyncClient: S3AsyncClient,
      s3Presigner: S3Presigner,
  ) extends OrganizationS3Client {

    private def createTempFileScoped: ZIO[Scope, InternalServerError.UnexpectedError, Path] =
      ZIO
        .acquireRelease(
          ZIO.attempt(Files.createTempFile("s3-upload-", ".tmp"))
        )(path => ZIO.attempt(Files.deleteIfExists(path)).ignoreLogged)
        .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create temp file", Some(e)))

    override def uploadLogo(
        organizationID: OrganizationID,
        organizationLogoFileName: OrganizationLogoFileName,
        organizationLogoBytes: ZStream[Any, Throwable, Byte],
    ): IO[ServiceError, OrganizationLogoBucketKey] =
      ZIO.scoped {
        for {
          organizationLogoBucketKey <- ZIO
            .fromEither(
              OrganizationLogoBucketKey.either(
                s"${organizationS3ClientConfig.organizationLogoKeyPrefix}/${organizationID.value}/${organizationLogoFileName.value}"
              )
            )
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError(s"Failed to create organization logo bucket key [$e]")
            )
          tempPath  <- ZIO.scoped(createTempFileScoped)
          bytesSize <- organizationLogoBytes
            .run(ZSink.fromPath(tempPath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to write organization logo to temp file", Some(e))
            )
          putObjectRequest <- ZIO
            .attempt(
              PutObjectRequest
                .builder()
                .bucket(organizationS3ClientConfig.organizationLogoBucket)
                .key(organizationLogoBucketKey.value)
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
              ServiceError.InternalServerError.UnexpectedError("Failed to upload organization logo to S3", Some(e))
            )
        } yield organizationLogoBucketKey
      }

    override def getLogoUrl(
        organizationLogoBucketKey: OrganizationLogoBucketKey
    ): IO[ServiceError, OrganizationLogoUrl] =
      for {
        getObjectRequest <- ZIO
          .attempt(
            GetObjectRequest
              .builder()
              .bucket(organizationS3ClientConfig.organizationLogoBucket)
              .key(organizationLogoBucketKey.value)
              .build()
          )
          .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectRequest", Some(e)))
        presignGetObjectRequest <- ZIO
          .attempt(
            GetObjectPresignRequest
              .builder()
              .getObjectRequest(getObjectRequest)
              .signatureDuration(organizationS3ClientConfig.organizationLogoUrlExpiresAtOffset)
              .build()
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to create GetObjectPresignRequest", Some(e))
          )
        urlRaw <- ZIO
          .attempt(s3Presigner.presignGetObject(presignGetObjectRequest).url())
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError("Failed to presignGetObject request", Some(e))
          )
        organizationLogoUrl <- ZIO
          .fromEither(
            OrganizationLogoUrl
              .either(urlRaw.toString)
          )
          .mapError(e =>
            ServiceError.InternalServerError.UnexpectedError(s"Failed to construct OrganizationLogoUrl: [$e]")
          )
      } yield organizationLogoUrl
  }

  private val s3AsyncClientLayer: ZLayer[OrganizationS3ClientConfig, Throwable, S3AsyncClient] =
    ZLayer.scoped(
      for {
        organizationS3ClientConfig <- ZIO.service[OrganizationS3ClientConfig]
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
        )(client =>
          ZIO.succeed(client.close()) <* ZIO.logError(
            "S3AsyncClient closed"
          )
        )
      } yield s3AsyncClient
    )

  private val s3PresignerLayer: ZLayer[OrganizationS3ClientConfig, Throwable, S3Presigner] =
    ZLayer.scoped(
      for {
        organizationS3ClientConfig <- ZIO.service[OrganizationS3ClientConfig]
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

  private def observed(organizationS3Client: OrganizationS3Client): OrganizationS3Client = new OrganizationS3Client {
    override def uploadLogo(
        organizationID: OrganizationID,
        organizationLogoFileName: OrganizationLogoFileName,
        organizationLogoBytes: ZStream[Any, Throwable, Byte],
    ): IO[ServiceError, OrganizationLogoBucketKey] =
      organizationS3Client.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)

    override def getLogoUrl(
        organizationLogoBucketKey: OrganizationLogoBucketKey
    ): IO[ServiceError, OrganizationLogoUrl] =
      organizationS3Client.getLogoUrl(organizationLogoBucketKey)
  }

  val live =
    (s3PresignerLayer ++ s3AsyncClientLayer) >>> ZLayer.derive[OrganizationS3ClientImpl] >>>
      ZLayer.fromFunction(observed)
}
