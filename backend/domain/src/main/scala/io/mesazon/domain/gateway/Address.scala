package io.mesazon.domain.gateway

case class Address(
    addressLine1: Option[AddressLine1],
    addressLine2: Option[AddressLine2],
    city: Option[City],
    postalCode: Option[PostalCode],
    country: Option[Country],
)
