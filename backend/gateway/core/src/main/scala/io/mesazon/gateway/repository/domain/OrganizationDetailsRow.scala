package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class OrganizationDetailsRow(
    organizationID: OrganizationID,
    name: OrganizationName,
    slug: OrganizationSlug,
    email: OrganizationEmail,
    phoneNumber: OrganizationPhoneNumber,
    organizationStage: OrganizationStage,
    addressLine1: OrganizationAddressLine1,
    addressLine2: Option[OrganizationAddressLine2],
    city: OrganizationCity,
    postalCode: OrganizationPostalCode,
    country: OrganizationCountry,
    logoFileName: Option[OrganizationLogoFileName],
    logoBucketKey: Option[OrganizationLogoBucketKey],
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
