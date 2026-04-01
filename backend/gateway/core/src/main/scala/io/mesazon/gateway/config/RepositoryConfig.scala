package io.mesazon.gateway.config

case class RepositoryConfig(
    schema: String,
    userOnboardTable: String = "",
    userDetailsTable: String = "",
    userContactTable: String = "",
    userOtpTable: String = "",
    wahaUserTable: String = "",
    wahaUserActivityTable: String = "",
    wahaUserMessageTable: String = "",
)

object RepositoryConfig {
  val live = deriveConfigLayer[RepositoryConfig]("repository")
}
