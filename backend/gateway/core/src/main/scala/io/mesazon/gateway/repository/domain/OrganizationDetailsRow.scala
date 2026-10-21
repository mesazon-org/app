package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class OrganizationDetailsRow(
    organizationID: OrganizationID,
    name: OrganizationName,
    slug: OrganizationSlug,
    tagline: Option[OrganizationTagline],
    emails: List[OrganizationEmailEntryRequest],
    phoneNumbers: List[OrganizationPhoneNumberEntryRequest],
    organizationStage: OrganizationStage,
    addresses: List[OrganizationAddressEntry],
    companyRegistrationNumber: Option[OrganizationCompanyRegistrationNumber],
    taxID: Option[OrganizationTaxID],
    logoImageAsset: Option[OrganizationLogoImageAsset],
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
