package io.mesazon.gateway.config

import software.amazon.awssdk.regions.Region
import sttp.model.Uri

case class OrganizationS3ClientConfig(
    useMock: Boolean,
    uri: Uri,
    region: Region,
    accessKeyId: String,
    secretAccessKey: String,
    organizationLogoBucket: String,
    organizationLogoKeyPrefix: String,
)

object OrganizationS3ClientConfig {

  val live = deriveConfigLayer[OrganizationS3ClientConfig]("organization-s3-client")
}
