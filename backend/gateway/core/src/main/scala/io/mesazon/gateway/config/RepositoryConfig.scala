package io.mesazon.gateway.config

case class RepositoryConfig(
    schema: String,
    userCredentialsTable: String = "",
    userDetailsTable: String = "",
    userOtpTable: String = "",
    userTokenTable: String = "",
    userContactTable: String = "",
    wahaUserTable: String = "",
    wahaUserActivityTable: String = "",
    wahaUserMessageTable: String = "",
)

object RepositoryConfig {
  val live = deriveConfigLayer[RepositoryConfig]("repository")
}
