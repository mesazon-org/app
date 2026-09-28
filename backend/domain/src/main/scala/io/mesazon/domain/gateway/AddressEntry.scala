package io.mesazon.domain.gateway

case class AddressEntry(
    addressLine1: AddressLine1,
    addressLine2: Option[AddressLine2],
    city: City,
    postalCode: PostalCode,
    country: Country,
    addressType: AddressType,
)
