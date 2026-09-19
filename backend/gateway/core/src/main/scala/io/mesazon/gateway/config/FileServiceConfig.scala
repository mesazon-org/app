package io.mesazon.gateway.config

case class FileServiceConfig(
    fileBytesMax: Long
)

object FileServiceConfig {

  val live = deriveConfigLayer[FileServiceConfig]("file-service")
}
