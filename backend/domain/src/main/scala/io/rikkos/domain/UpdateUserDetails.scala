package io.rikkos.domain

final case class UpdateUserDetails(
    userID: UserID,
    firstName: Option[FirstName],
    lastName: Option[LastName],
    countryCode: Option[CountryCode],
    phoneNumber: Option[PhoneNumber],
    addressLine1: Option[AddressLine1],
    addressLine2: Option[AddressLine2],
    city: Option[City],
    postalCode: Option[PostalCode],
    company: Option[Company],
)
