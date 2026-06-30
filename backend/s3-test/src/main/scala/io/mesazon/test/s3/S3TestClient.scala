package io.mesazon.test.s3

import com.dimafeng.testcontainers.*
import io.mesazon.test.s3.S3TestClient.S3TestClientConfig
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.*
import software.amazon.awssdk.services.s3.model.*
import zio.*
import zio.stream.*

import java.net.URI
import scala.jdk.CollectionConverters.IterableHasAsScala

case class S3TestClient(
    s3TestClientConfig: S3TestClientConfig
) {

  private val credentialsProvider = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("test", "test")
  )

  private val s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build()

  private def s3ClientScope: ZIO[Scope, Throwable, S3Client] =
    ZIO.acquireRelease(
      ZIO.attempt {
        S3Client
          .builder()
          .region(s3TestClientConfig.region)
          .endpointOverride(s3TestClientConfig.uri)
          .credentialsProvider(credentialsProvider)
          .serviceConfiguration(s3Configuration)
          .build()
      }
    )(client => ZIO.succeed(client.close()))

  def getObject(bucket: String, key: String): Task[Chunk[Byte]] =
    ZIO.scoped {
      (for {
        getObjectRequest <- ZStream.fromZIO(
          ZIO.attempt(
            GetObjectRequest
              .builder()
              .bucket(bucket)
              .key(key)
              .build()
          )
        )
        s3Client    <- ZStream.fromZIO(s3ClientScope)
        inputStream <- ZStream.fromZIO(ZIO.attemptBlocking(s3Client.getObject(getObjectRequest)))
        byte        <- ZStream.fromInputStream(inputStream)
      } yield byte).runCollect
    }

  def emptyAllBuckets(): Task[Unit] =
    ZIO.scoped {
      for {
        s3Client            <- s3ClientScope
        listBucketsResponse <- ZIO.attempt(s3Client.listBuckets())
        buckets = listBucketsResponse.buckets().asScala
        _ <- ZIO.foreachDiscard(buckets) { bucket =>
          val bucketName = bucket.name()
          for {
            listObjectsResponse <- ZIO.attempt(
              s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucketName).build())
            )
            objects = listObjectsResponse.contents().asScala
            _ <- ZIO.foreach(objects) { obj =>
              ZIO.attempt(
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(obj.key()).build())
              )
            }
          } yield ()
        }
      } yield ()
    }
}

object S3TestClient {
  lazy val ServiceName     = "s3"
  lazy val ServicePort     = 9090
  lazy val ExposedServices = Set(ExposedService(ServiceName, ServicePort))

  case class S3TestClientConfig(
      host: String = "localhost",
      port: Int = ServicePort,
      region: Region = Region.US_EAST_1,
  ) {
    def uri: URI = URI.create(s"http://$host:$port")
  }

  object S3TestClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   S3TestClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
    ): S3TestClientConfig = {
      val host = containers.getServiceHost(serviceName, servicePort)
      val port = containers.getServicePort(serviceName, servicePort)

      S3TestClientConfig(host = host, port = port)
    }
  }

  val live = ZLayer.fromFunction(S3TestClient.apply)
}
