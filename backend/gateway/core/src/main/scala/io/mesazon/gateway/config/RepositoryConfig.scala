package io.mesazon.gateway.config

case class RepositoryConfig(
    schema: String,
    userCredentialsTable: String = "",
    userActionAttemptTable: String = "",
    userDetailsTable: String = "",
    userOtpTable: String = "",
    userTokenTable: String = "",
    organizationDetailsTable: String = "",
    organizationUserTable: String = "",
    wahaUserTable: String = "",
    wahaUserActivityTable: String = "",
    wahaUserMessageTable: String = "",
) {
  val allTableNames = List(
    userCredentialsTable,
    userActionAttemptTable,
    userDetailsTable,
    userOtpTable,
    userTokenTable,
    organizationDetailsTable,
    organizationUserTable,
    wahaUserTable,
    wahaUserActivityTable,
    wahaUserMessageTable,
  ).filter(_.nonEmpty)
}

object RepositoryConfig {
  val live = deriveConfigLayer[RepositoryConfig]("repository")
}
