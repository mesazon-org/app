package io.mesazon.gateway.clients

import io.mesazon.domain.gateway.ServiceError.InternalServerError
import io.mesazon.domain.gateway.{OrganizationID, OrganizationLogoFileName, ServiceError}
import io.mesazon.gateway.config.OrganizationS3ClientConfig
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}
import zio.*
import zio.stream.{ZSink, ZStream}

import java.nio.file.{Files, Path}
import scala.util.chaining.scalaUtilChainingOps

trait OrganizationS3Client {
  def uploadLogo(
      organizationID: OrganizationID,
      organizationLogoFileName: OrganizationLogoFileName,
      organizationLogoBytes: ZStream[Any, Throwable, Byte],
  ): IO[ServiceError, String]
}

object OrganizationS3Client {

  final class OrganizationS3ClientImpl(
      organizationS3ClientConfig: OrganizationS3ClientConfig,
      s3AsyncClient: S3AsyncClient,
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
    ): IO[ServiceError, String] =
      ZIO.scoped {
        for {
          _   = println("Starting uploadLogo")
          key =
            s"${organizationS3ClientConfig.organizationLogoKeyPrefix}/${organizationID.value}/${organizationLogoFileName.value}"
          tempPath     <- ZIO.scoped(createTempFileScoped)
          bytesWritten <- organizationLogoBytes
            .run(ZSink.fromPath(tempPath))
            .mapError(e =>
              ServiceError.InternalServerError
                .UnexpectedError("Failed to write organization logo to temp file", Some(e))
            )
          _ = println(s"Wrote $bytesWritten bytes to temp file at $tempPath")
          fileSize <- ZIO
            .attempt(Files.size(tempPath))
            .mapError(e => ServiceError.InternalServerError.UnexpectedError("Failed to get size of temp file", Some(e)))
          _ = println(s"Size of temp file: $fileSize bytes")
          putObjectRequest <- ZIO
            .attempt(
              PutObjectRequest
                .builder()
                .bucket(organizationS3ClientConfig.organizationLogoBucket)
                .key(key)
                .contentLength(fileSize)
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
        } yield key
      }
  }

  private val s3AsyncClientLayer: ZLayer[OrganizationS3ClientConfig, Throwable, S3AsyncClient] =
    ZLayer.scoped(
      for {
        organizationS3ClientConfig <- ZIO.service[OrganizationS3ClientConfig]
        _ = println("Creating S3AsyncClient with config: " + organizationS3ClientConfig)
        s3AsyncClient <- ZIO.acquireRelease(
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
          ZIO.succeed(println("Closing resource")) *> ZIO.succeed(client.close()) <* ZIO.logError(
            "S3AsyncClient closed"
          )
        )
        _ = println("S3AsyncClient created successfully")
      } yield s3AsyncClient
    )

  private def observed(organizationS3Client: OrganizationS3Client): OrganizationS3Client = new OrganizationS3Client {
    override def uploadLogo(
        organizationID: OrganizationID,
        organizationLogoFileName: OrganizationLogoFileName,
        organizationLogoBytes: ZStream[Any, Throwable, Byte],
    ): IO[ServiceError, String] =
      organizationS3Client.uploadLogo(organizationID, organizationLogoFileName, organizationLogoBytes)
  }

  val live = s3AsyncClientLayer >>> ZLayer.derive[OrganizationS3ClientImpl] >>> ZLayer.fromFunction(observed)
}
