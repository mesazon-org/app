package io.mesazon.gateway.config

case class CatalogueFileServiceConfig(
    uploadCatalogueItemImageFileBytesMax: Long,
    extractCatalogueItemsFileBytesMax: Long,
)

object CatalogueFileServiceConfig {

  val live = deriveConfigLayer[CatalogueFileServiceConfig]("catalogue-file-service")
}
