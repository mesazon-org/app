package io.mesazon.gateway.config

case class RepositoryConfig(
    schema: String,
    userCredentialsTable: String = "",
    userDetailsTable: String = "",
    userOtpTable: String = "",
    userTokenTable: String = "",
    wahaUserTable: String = "",
    wahaUserActivityTable: String = "",
    wahaUserMessageTable: String = "",
) {
  val allTableNames = List(
    userCredentialsTable,
    userDetailsTable,
    userOtpTable,
    userTokenTable,
    wahaUserTable,
    wahaUserActivityTable,
    wahaUserMessageTable,
  ).filter(_.nonEmpty)
}

object RepositoryConfig {
  val live = deriveConfigLayer[RepositoryConfig]("repository")
}
