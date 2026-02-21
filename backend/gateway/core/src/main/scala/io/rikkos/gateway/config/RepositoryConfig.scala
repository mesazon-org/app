package io.rikkos.gateway.config

case class RepositoryConfig(
    schema: String,
    wahaUsersTable: String,
    wahaUserActivityTable: String,
    wahaUserMessagesTable: String,
)

object RepositoryConfig {
  val live = deriveConfigLayer[RepositoryConfig]("repository")
}
