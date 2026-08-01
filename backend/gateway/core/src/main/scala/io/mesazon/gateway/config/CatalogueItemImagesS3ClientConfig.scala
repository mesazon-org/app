package io.mesazon.gateway.config

import software.amazon.awssdk.regions.Region
import sttp.model.Uri
import zio.Duration

case class CatalogueItemImagesS3ClientConfig(
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

object CatalogueItemImagesS3ClientConfig {

  val live = deriveConfigLayer[CatalogueItemImagesS3ClientConfig]("catalogue-item-images-s3-client")
}
