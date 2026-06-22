package io.mesazon.gateway.config

import software.amazon.awssdk.regions.Region
import sttp.model.Uri
import zio.Duration

case class OrganizationS3ClientConfig(
    useMock: Boolean,
    uri: Uri,
    region: Region,
    accessKeyId: String,
    secretAccessKey: String,
    organizationLogoBucket: String,
    organizationLogoPathPrefix: String,
    organizationLogoUrlExpiresAtOffset: Duration,
)

object OrganizationS3ClientConfig {

  val live = deriveConfigLayer[OrganizationS3ClientConfig]("organization-s3-client")
}
