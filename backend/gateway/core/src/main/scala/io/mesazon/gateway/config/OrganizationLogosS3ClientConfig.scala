package io.mesazon.gateway.config

import software.amazon.awssdk.regions.Region
import sttp.model.Uri
import zio.Duration

case class OrganizationLogosS3ClientConfig(
    useMock: Boolean,
    uri: Uri,
    region: Region,
    accessKeyId: String,
    secretAccessKey: String,
    bucket: String,
    bucketPathPrefix: String,
    originalFileName: String,
    normalizedFileName: String,
    urlExpiresAtOffset: Duration,
)

object OrganizationLogosS3ClientConfig {

  val live = deriveConfigLayer[OrganizationLogosS3ClientConfig]("organization-logos-s3-client")
}
