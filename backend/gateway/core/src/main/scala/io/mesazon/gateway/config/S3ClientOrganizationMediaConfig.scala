package io.mesazon.gateway.config

import software.amazon.awssdk.regions.Region
import sttp.model.Uri
import zio.Duration

case class S3ClientOrganizationMediaConfig(
    useMock: Boolean,
    uri: Uri,
    region: Region,
    accessKeyId: String,
    secretAccessKey: String,
    bucket: String,
    catalogueItemImageBucketPathPrefix: String,
    organizationLogoBucketPathPrefix: String,
    originalFileName: String,
    normalizedFileName: String,
    urlExpiresAtOffset: Duration,
)

object S3ClientOrganizationMediaConfig {

  val live = deriveConfigLayer[S3ClientOrganizationMediaConfig]("s3-client-organization-media")
}
