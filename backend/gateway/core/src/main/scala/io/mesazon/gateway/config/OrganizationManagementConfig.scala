package io.mesazon.gateway.config

import zio.*

case class OrganizationManagementConfig(
    sendOrganizationCreatedEmailMaxRetries: Int,
    sendOrganizationCreatedEmailRetryDelay: Duration,
)

object OrganizationManagementConfig {
  val live = deriveConfigLayer[OrganizationManagementConfig]("organization-management")
}
