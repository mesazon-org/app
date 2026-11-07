package io.mesazon.gateway.config

case class CustomerBookFileServiceConfig(
    extractCustomersFileBytesMax: Long
)

object CustomerBookFileServiceConfig {

  val live = deriveConfigLayer[CustomerBookFileServiceConfig]("customer-book-file-service")
}
