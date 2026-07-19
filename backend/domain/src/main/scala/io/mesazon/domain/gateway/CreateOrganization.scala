package io.mesazon.domain.gateway

// Contact points (each carries an isDefault flag)

case class OrganizationEmailEntry(
    email: OrganizationEmail,
    isDefault: Boolean,
)

case class OrganizationPhoneNumberEntry(
    phoneNumber: OrganizationPhoneNumber,
    isDefault: Boolean,
)

case class CreateOrganization(
    name: OrganizationName,
    slug: OrganizationSlug,
    tagline: Option[OrganizationTagline],
    emails: List[OrganizationEmailEntry],
    phoneNumbers: List[OrganizationPhoneNumberEntry],
    addressLine1: Option[OrganizationAddressLine1],
    addressLine2: Option[OrganizationAddressLine2],
    city: Option[OrganizationCity],
    postalCode: Option[OrganizationPostalCode],
    country: Option[OrganizationCountry],
    companyRegistrationNumber: Option[OrganizationCompanyRegistrationNumber],
    taxID: Option[OrganizationTaxID],
)
