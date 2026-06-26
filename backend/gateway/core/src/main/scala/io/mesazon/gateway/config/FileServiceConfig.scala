package io.mesazon.gateway.config

case class FileServiceConfig(
    maxOrganizationLogoBytes: Long
)

object FileServiceConfig {

  val live = deriveConfigLayer[FileServiceConfig]("file-service")
}
