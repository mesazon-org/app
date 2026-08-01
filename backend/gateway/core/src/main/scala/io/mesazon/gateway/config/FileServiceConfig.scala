package io.mesazon.gateway.config

case class FileServiceConfig(
    maxUploadBytes: Long
)

object FileServiceConfig {

  val live = deriveConfigLayer[FileServiceConfig]("file-service")
}
