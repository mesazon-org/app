package io.mesazon.gateway.config

import io.mesazon.domain.gateway.*

case class FileServiceConfig(
    maxOrganizationLogoBytes: Long,
    organizationOriginalLogoFileName: OrganizationOriginalLogoFileName,
    organizationNormalizedLogoFileName: OrganizationNormalizedLogoFileName,
    organizationWhatsAppLogoFileName: OrganizationWhatsAppLogoFileName,
)

object FileServiceConfig {

  val live = deriveConfigLayer[FileServiceConfig]("file-service")
}