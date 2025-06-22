package io.rikkos.gateway.config

final case class DatabaseConfig(
    name: String,
    driver: String,
    host: String,
    port: Int,
    username: String,
    password: String,
    threadPoolSize: Int,
) {
  val url: String = s"jdbc:postgresql://$host:$port/$name"
}

object DatabaseConfig {

  val live = deriveConfigLayer[DatabaseConfig]("database")
}
