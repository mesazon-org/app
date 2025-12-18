package io.rikkos.domain

case class UpdateUserDetails(
    firstName: Option[FirstName],
    lastName: Option[LastName],
    phoneNumber: Option[PhoneNumber],
    addressLine1: Option[AddressLine1],
    addressLine2: Option[AddressLine2],
    city: Option[City],
    postalCode: Option[PostalCode],
    company: Option[Company],
)
