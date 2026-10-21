package io.mesazon.domain.gateway

// Contact points (each carries an isDefault flag)

case class OrganizationEmailEntryRequest(
    email: OrganizationEmail,
    isDefault: Boolean,
)

case class OrganizationPhoneNumberEntryRequest(
    phoneNumber: OrganizationPhoneNumber,
    isDefault: Boolean,
)

// Requests

case class CreateOrganizationPostRequest(
    name: OrganizationName,
    slug: OrganizationSlug,
    tagline: Option[OrganizationTagline],
    emails: List[OrganizationEmailEntryRequest],
    phoneNumbers: List[OrganizationPhoneNumberEntryRequest],
    addresses: List[OrganizationAddressEntry],
    companyRegistrationNumber: Option[OrganizationCompanyRegistrationNumber],
    taxID: Option[OrganizationTaxID],
)
