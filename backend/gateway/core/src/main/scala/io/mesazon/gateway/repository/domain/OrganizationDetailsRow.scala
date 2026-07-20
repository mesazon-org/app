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
    addressLine1: Option[OrganizationAddressLine1],
    addressLine2: Option[OrganizationAddressLine2],
    city: Option[OrganizationCity],
    postalCode: Option[OrganizationPostalCode],
    country: Option[OrganizationCountry],
    companyRegistrationNumber: Option[OrganizationCompanyRegistrationNumber],
    taxID: Option[OrganizationTaxID],
    logoOriginalBucketKey: Option[OrganizationLogoOriginalBucketKey],
    logoNormalizedBucketKey: Option[OrganizationLogoNormalizedBucketKey],
    logoOriginalFileName: Option[OrganizationLogoOriginalFileName],
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
