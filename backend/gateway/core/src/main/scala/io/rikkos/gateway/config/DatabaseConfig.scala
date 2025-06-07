package io.rikkos.gateway.config

final case class DatabaseConfig(name: String, driver: String, host: String, port: Int, user: String, password: String)

object DatabaseConfig {

  val live = deriveConfigLayer[DatabaseConfig]("database")
}
