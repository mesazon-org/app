package io.mesazon.domain.gateway

case class CreateOrganization(
    name: OrganizationName,
    slug: OrganizationSlug,
    email: OrganizationEmail,
    phoneNumber: OrganizationPhoneNumber,
    addressLine1: OrganizationAddressLine1,
    addressLine2: Option[OrganizationAddressLine2],
    city: OrganizationCity,
    postalCode: OrganizationPostalCode,
    country: OrganizationCountry,
    companyRegistrationNumber: Option[OrganizationCompanyRegistrationNumber],
    taxID: Option[OrganizationTaxID],
)
