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

  def getObject(bucket: String, key: String): Stream[Throwable, Byte] =
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
      s3Client <- ZStream.fromZIO(s3ClientScope)
      byte     <- ZStream.fromInputStream(s3Client.getObject(getObjectRequest))
    } yield byte).provideLayer(Scope.default)
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
      *   PostgreSQLTestClientConfig
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
